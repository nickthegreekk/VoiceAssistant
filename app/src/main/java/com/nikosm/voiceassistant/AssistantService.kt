package com.nikosm.voiceassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private var vadDetector: VADDetector? = null
private var vadRecorder: VADAudioRecorder? = null

// ---------------------------------------------------------------------------
// Cloud model list selection — tier diversity, not pure recency.
// Pure "top N by date" drops specialty tiers whenever several flagships launch
// close together. Two confirmed cases: Claude Haiku 4.5 (fast/cheap tier)
// vanished behind the Sonnet/Opus/Fable 5 releases, and DeepSeek R1-0528
// (reasoning specialist) is excluded by the newer V4 wave — neither competes
// on "newness", they serve different purposes. The selection below guarantees
// a slot for the most recent model of each available specialty tier alongside
// the most recent flagship-tier models.
// ---------------------------------------------------------------------------
private const val MAX_CLOUD_MODELS_SHOWN = 4

// Cross-provider "fast/small" tier signals in model names: Anthropic Haiku,
// OpenAI mini/nano, Google flash (incl. flash-lite), generic "lite".
private val SMALL_TIER_MODEL_PATTERNS = listOf("haiku", "mini", "nano", "flash", "lite")

// Cross-provider "reasoning specialist" signals: DeepSeek R1, "*-reasoner"
// style names, and explicit "thinking" variants.
private val REASONING_TIER_MODEL_PATTERNS = listOf("r1", "reasoner", "thinking")

private fun isSmallTierModel(name: String): Boolean {
    val n = name.lowercase()
    return SMALL_TIER_MODEL_PATTERNS.any { n.contains(it) }
}

private fun isReasoningTierModel(name: String): Boolean {
    val n = name.lowercase()
    return REASONING_TIER_MODEL_PATTERNS.any { n.contains(it) }
}

/**
 * Picks the subset shown per provider from a most-recent-first list: the most
 * recent model of each available specialty tier (fast/cheap, reasoning) is
 * always included when one exists, then the list is filled with the most
 * recent remaining models up to [maxCount]. Returned in the input (recency)
 * order.
 */
private fun <T> selectDiverseModelSubset(models: List<T>, maxCount: Int, nameOf: (T) -> String): List<T> {
    if (models.size <= maxCount) return models
    // Classify into three broad tiers. Reasoning takes precedence over the
    // small/fast check, since a name like "r1-lite" is first a reasoning model.
    val reasoningTier = mutableListOf<T>()
    val smallTier = mutableListOf<T>()
    val flagshipTier = mutableListOf<T>()
    for (m in models) {
        val n = nameOf(m).lowercase()
        when {
            isReasoningTierModel(n) -> reasoningTier.add(m)
            isSmallTierModel(n) -> smallTier.add(m)
            else -> flagshipTier.add(m)
        }
    }
    val chosen = LinkedHashSet<T>()
    flagshipTier.firstOrNull()?.let { chosen.add(it) }   // newest flagship
    smallTier.firstOrNull()?.let { chosen.add(it) }      // newest fast/cheap
    reasoningTier.firstOrNull()?.let { chosen.add(it) }  // newest reasoning specialist
    for (m in models) {
        if (chosen.size >= maxCount) break
        chosen.add(m)
    }
    return models.filter { chosen.contains(it) }
}

class AssistantService : Service() {

    private val binder = AssistantBinder()
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var settingsManager: SettingsManager

    val _state = MutableStateFlow(AssistantState.IDLE)
    val assistantState = _state.asStateFlow()

    val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    // Stage-1 streaming (Direct-Ollama only): the progressive response text
    // while a stream is in flight. The UI renders this as a trailing
    // in-progress message (typing cursor). _messages is touched exactly ONCE
    // per turn — at genuine completion, a single final append — never during
    // the stream. Nulled on completion-apply, on any guard failure, and in the
    // chat flows' finally as a belt-and-braces reset.
    val _streamingText = MutableStateFlow<String?>(null)
    val streamingText = _streamingText.asStateFlow()

    // Stage-2 streaming TTS: live playback fraction (0.0→1.0) for the karaoke
    // highlight. Driven by the chunked player's polling coroutine (actual
    // MediaPlayer position + cumulative prior chunk durations, mapped against
    // a progressively refined total-duration estimate). Null when no chunked
    // TTS is active — the views then show their classic behavior (typewriter
    // reveal / plain text). Consumed by all three transcript views (HUD,
    // classic mini-box, text-mode ChatList) via ttsActiveWordRange().
    val _ttsPlaybackFraction = MutableStateFlow<Float?>(null)
    val ttsPlaybackFraction = _ttsPlaybackFraction.asStateFlow()

    // Stage-2 word timestamps: [{word, start, end}] in absolute seconds (from
    // the chunked player's synthesis responses, adjusted for cumulative
    // chunk offsets). Null when no timestamps available (eSpeak/System TTS,
    // synthesis without timestamps, etc.). Feeds ttsActiveWordRange() with
    // REAL Kokoro alignment data when available (estimated tail otherwise).
    val _ttsWordTimestamps = MutableStateFlow<String?>(null)
    val ttsWordTimestamps = _ttsWordTimestamps.asStateFlow()

    // Fix #3: one-shot "re-run the timed reveal for this message" signal, emitted
    // BEFORE a NON-chunked replay starts (device-voice engine or a stored file) so a
    // replay gets the same duration-synced typewriter reveal a live first response
    // gets. Null until the first replay is requested; the UI consumes each token
    // exactly once. Not emitted by the chunked Gateway path, which has its own
    // karaoke highlight. See RevealRestartRequest.
    val _revealRestartRequest = MutableStateFlow<RevealRestartRequest?>(null)
    val revealRestartRequest = _revealRestartRequest.asStateFlow()

    // Real-time microphone amplitude (0.0 - 1.0), used by the Celestial HUD
    // visualizer during LISTENING. Polled from MediaRecorder in startRecording()
    // and pushed from VADAudioRecorder in startVadListening(). Reset to 0f
    // whenever LISTENING ends.
    val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude = _micAmplitude.asStateFlow()

    // Index of the in-flight Direct-Ollama streaming placeholder inside
    // _messages (appended on the stream's first chunk, replaced by the final
    // message at apply, removed if it ended up blank). Nullable — null when no
    // stream is in flight or the placeholder was already resolved.
    //
    // H3 (ownership): the index alone is service-global state, so a STALE turn
    // unwinding (superseded mid-stream, or Stop pressed while it was blocked on a
    // read) could read/clear the placeholder of whatever NEWER turn was streaming
    // by then — deleting that newer turn's in-progress bubble, or finalizing the
    // wrong index. Every placeholder therefore records the chat-request generation
    // it belongs to, and every read/write/clear is gated on the caller owning it
    // (see takeStreamedPlaceholderIfOwned / releaseStreamedPlaceholderIfOwned /
    // removeBlankPlaceholderSvc). Same treatment for the published streaming text:
    // _streamingText drives the trailing streaming bubble in the UI, and a stale
    // clear used to blank the newer turn's partially-streamed content (producing a
    // duplicated/empty bubble pair when the stale catch then also appended).
    internal var streamedPlaceholderIndex: Int? = null

    // Generation that owns `streamedPlaceholderIndex` (null ⇒ no live placeholder).
    @Volatile
    internal var streamedPlaceholderGeneration: Long? = null

    // Generation that owns the currently published `_streamingText`
    // (null ⇒ nothing published). Tracked separately from the placeholder index
    // because a stream publishes progressive text on every chunk while the
    // placeholder is only adopted once.
    @Volatile
    internal var streamingTextGeneration: Long? = null

    /**
     * H3: true when [generation] is the RECORDED owner of the placeholder — i.e. the
     * placeholder in `_messages` is this turn's own in-progress bubble.
     *
     * This single check is what excludes a stale turn from touching a NEWER turn's
     * placeholder: the newer turn's own `adoptStreamedPlaceholder` re-records ownership,
     * so the stale turn's recorded generation no longer matches and null is returned.
     *
     * Deliberately does NOT additionally require isChatRequestCurrent. Two reasons:
     *  - Cleanup of a turn's OWN artifacts (blank-bubble removal, releasing the index,
     *    clearing its published overlay text) must still happen when that turn was
     *    superseded or Stopped — Stop bumps the sequence synchronously, so the unwinding
     *    turn is *never* current, and a currency requirement would strand its blank
     *    bubble in the transcript and leave its streamed overlay text stuck on screen.
     *  - Recorded-ownership matching ALREADY makes cross-turn clobbering impossible,
     *    which is the property H3 is about.
     *
     * Currency is enforced where it actually matters — on WRITES into the live
     * transcript/UI — by [ownsStreamedPlaceholderSvc] for the progressive writers, and by
     * each flow's existing isChatRequestCurrent apply/catch/finally gates.
     */
    internal fun ownsStreamedPlaceholderRecord(generation: Long): Boolean =
        streamedPlaceholderGeneration == generation

    /**
     * H3: true when [generation] is the recorded placeholder owner AND still the current
     * request — i.e. this turn may still write into the LIVE transcript as if nothing had
     * happened. Used by the progressive writers (per-chunk placeholder text): those must
     * not repaint a bubble once the turn is dead.
     */
    internal fun ownsStreamedPlaceholderSvc(generation: Long): Boolean =
        isChatRequestCurrent(generation) && ownsStreamedPlaceholderRecord(generation)

    /** H3: true when [generation] is the recorded owner of the published streaming text. */
    internal fun ownsStreamingText(generation: Long): Boolean =
        streamingTextGeneration == generation

    /** H3: the recorded placeholder index of [generation], or null when it is not the owner. */
    internal fun streamedPlaceholderIndexIfRecorded(generation: Long): Int? =
        if (ownsStreamedPlaceholderRecord(generation)) streamedPlaceholderIndex else null

    /** H3: adopts [index] as [generation]'s in-progress placeholder (overwrites a stale owner). */
    internal fun adoptStreamedPlaceholder(generation: Long, index: Int) {
        streamedPlaceholderIndex = index
        streamedPlaceholderGeneration = generation
    }

    /**
     * H3: consumes this generation's placeholder index, or null when the caller no
     * longer owns it (a newer turn adopted its own placeholder, or the index was already
     * released). A null result means "do not touch the message list by index" — the
     * caller falls back to a plain append.
     *
     * Recorded-ownership gated: the callers (apply blocks) are already behind an
     * isChatRequestCurrent gate, and a claimed index can only ever be the caller's OWN.
     */
    internal fun takeStreamedPlaceholderIfOwned(generation: Long): Int? {
        if (!ownsStreamedPlaceholderRecord(generation)) return null
        val idx = streamedPlaceholderIndex
        streamedPlaceholderIndex = null
        streamedPlaceholderGeneration = null
        return idx
    }

    /** H3: drops this generation's placeholder ownership WITHOUT touching _messages. */
    internal fun releaseStreamedPlaceholderIfOwned(generation: Long) {
        if (!ownsStreamedPlaceholderRecord(generation)) return
        streamedPlaceholderIndex = null
        streamedPlaceholderGeneration = null
    }

    /** H3: publishes progressive streamed text on behalf of [generation]. */
    internal fun publishStreamingText(generation: Long, text: String?) {
        _streamingText.value = text
        streamingTextGeneration = if (text == null) null else generation
    }

    /** H3: clears the published streaming text only when [generation] owns it. */
    internal fun clearStreamingTextIfOwned(generation: Long) {
        if (!ownsStreamingText(generation)) return
        _streamingText.value = null
        streamingTextGeneration = null
    }

    /**
     * H3: removes this generation's placeholder from `_messages` if it is still blank.
     *
     * Recorded-ownership gated (NOT currency): a turn that was superseded or Stopped must
     * still clear its OWN blank bubble — Stop bumps the sequence synchronously, so the
     * unwinding turn is never "current" and a currency gate would strand the bubble. It
     * can still never reach a newer turn's placeholder, because that turn re-recorded
     * ownership. A placeholder holding partial text is deliberately KEPT (D2: a stopped
     * stream's partial response stays as real history).
     */
    internal fun removeBlankPlaceholderSvc(generation: Long) {
        if (!ownsStreamedPlaceholderRecord(generation)) return
        val idx = streamedPlaceholderIndex ?: run {
            streamedPlaceholderGeneration = null
            return
        }
        val current = _messages.value.toMutableList()
        if (idx in current.indices && current[idx].text.isBlank()) {
            current.removeAt(idx)
            _messages.value = current
        }
        streamedPlaceholderIndex = null
        streamedPlaceholderGeneration = null
    }

    // RAG knowledge-base upload progress/outcome. Hoisted to the service (instead of
    // dialog-local remember state) so it survives Settings-dialog closure: the uploads
    // run on serviceScope and the dialog just collects this flow for display.
    val _knowledgeUploadStatus = MutableStateFlow("")
    val knowledgeUploadStatus = _knowledgeUploadStatus.asStateFlow()

    // The active persona's NAME — the single source of truth for "who is selected".
    //
    // Backed by a flow rather than a plain field so the selection is *observable*. It was a
    // bare `var`, which meant nothing owned the selection: the service published the persona
    // LIST and the MESSAGES, while "which one is active" was only readable by polling. Every
    // consumer that needed to react therefore had to be told separately, by hand — which is
    // exactly how a persona switch could move the service while a UI mirror kept showing the
    // previous persona (the spontaneous-message deep-link bug).
    //
    // Deliberately still a `var` with the same name and type, backed by the flow: every
    // existing writer — switchPersona, the rename path, the delete fallback, the cold-start
    // seed — and every test that stages a scenario by assigning it keep working unchanged,
    // and each write now PUBLISHES automatically instead of having to remember to.
    // The backing flow is declared BEFORE the facade so the accessors can never touch a field
    // that has not been initialised yet: properties initialise in declaration order, and a
    // getter reading a later-declared field would read null (or throw) during construction.
    private val _currentPersonaName = MutableStateFlow<String?>(null)
    internal var currentPersonaName: String?
        get() = _currentPersonaName.value
        set(value) { _currentPersonaName.value = value }

    // L5: persona history lives in SharedPreferences as one JSON blob per persona, and both
    // switchPersona and clearMessages used to read/write it ON THE MAIN THREAD — a persona tap
    // serialized the outgoing history, wrote it, and parsed the incoming one inline. That IO now
    // runs on Dispatchers.IO, so a swap is no longer instantaneous and needs bookkeeping:
    //  - messagesOwnerName: the persona whose history `_messages` currently holds. It equals
    //    currentPersonaName in every settled state; the two differ only while a swap's load is
    //    still in flight (the name has already moved to the incoming persona while the outgoing
    //    messages are still on screen). persistSettings saves THIS pair — never "live name +
    //    live messages" — so a save from onTaskRemoved/onDestroy/the settings UI can never file
    //    one persona's messages under another persona's name. internal (like currentPersonaName)
    //    so tests can stage that mid-swap state.
    //  - personaHistoryLoadSeq: bumped when a swap/clear starts; a load only publishes if it is
    //    still the newest one, so fast A->B->A taps cannot show an older persona's messages.
    //  - historyIoJob: the running history operation. The next one joins it, so a clear and a
    //    swap (or two swaps) can never interleave their write/read — the old synchronous
    //    save-then-load ordering is preserved, just off Main.
    // All three are written only from Main — a Main-thread entry point or a job body that
    // resumes on Main — like currentPersonaName. The debounced persistSettings() reads
    // messagesOwnerName from Dispatchers.IO, exactly the (benign, best-effort) cross-thread
    // read it has always done for currentPersonaName.
    internal var messagesOwnerName: String? = null
    private var personaHistoryLoadSeq: Long = 0
    private var historyIoJob: Job? = null

    // L5: awaits the running persona-history operation (swap/clear). Persona-history IO is
    // asynchronous now, so anything that needs a SETTLED swap — an instrumented test asserting
    // that the outgoing history was saved, or that the incoming one was loaded — joins here
    // instead of racing the IO. No-op when no operation is in flight.
    internal suspend fun awaitPersonaHistoryIo() {
        historyIoJob?.join()
    }

    // A2/B1: monotonically increasing chat request sequence. Bumped every time a new
    // chat request starts; a response may only be APPLIED if it belongs to the most
    // recent request (isChatRequestCurrent) AND the active persona is unchanged
    // (isChatContextCurrent). State ownership (catch handling / finally resets)
    // follows the sequence only, so a persona switch mid-request still cleans up
    // state without polluting the new persona's history.
    private var chatRequestSeq: Long = 0
    internal fun nextChatRequestSeq(): Long = ++chatRequestSeq
    internal fun isChatRequestCurrent(seq: Long): Boolean = seq == chatRequestSeq
    // B4: read-only accessor for staleness guards outside the chat flows (e.g.
    // testGatewayVoice): capture the current generation WITHOUT bumping it, so a
    // non-chat action is invalidated by Stop or a newer chat request but never
    // invalidates anything else itself.
    internal fun currentChatRequestSeq(): Long = chatRequestSeq
    internal fun isChatContextCurrent(seq: Long, personaName: String): Boolean =
        seq == chatRequestSeq && currentPersonaName == personaName

    // M2 (ownership): the generation of the turn that currently OWNS a THINKING state —
    // and, through it, the audio focus a voice turn retains across THINKING
    // (stopRecording keeps it; only the owning turn's terminal path releases it).
    //
    // Recorded at every THINKING entry and released by the owning turn's own terminal
    // reset, so `owner != null && isChatRequestCurrent(owner)` is a GENUINE in-flight
    // check rather than a restatement of `state == THINKING`: a THINKING whose owner has
    // since been superseded or Stopped fails the currency half and is normalized to IDLE.
    //
    // Read by stopVadListening() (and its test) so toggling hands-free off can no longer
    // force IDLE underneath a live turn. That force-reset made the live turn's own
    // `_state.value == THINKING` finally guard fail, so the turn skipped abandoning the
    // focus it had retained through THINKING — with the UI already on IDLE, nothing could
    // ever release it and other apps stayed ducked indefinitely.
    @Volatile
    internal var thinkingOwnerGeneration: Long? = null

    /** M2: records [generation] as the owner of the THINKING state entered next. */
    internal fun recordThinkingOwner(generation: Long) {
        thinkingOwnerGeneration = generation
    }

    /** M2: releases THINKING ownership held by [generation] (no-op for a stale owner). */
    internal fun clearThinkingOwnerIfOwned(generation: Long) {
        if (thinkingOwnerGeneration == generation) thinkingOwnerGeneration = null
    }

    val _sessionUsage = MutableStateFlow(UsageInfo())
    val sessionUsage = _sessionUsage.asStateFlow()

    val _totalCost = MutableStateFlow(0.0)
    val totalCost = _totalCost.asStateFlow()

    val _voiceDuration = MutableStateFlow(0)
    val voiceDuration = _voiceDuration.asStateFlow()

    val _lastWorkingBase = MutableStateFlow<String?>(null)
    val lastWorkingBase = _lastWorkingBase.asStateFlow()

    val _earpieceMode = MutableStateFlow(false)
    val earpieceMode = _earpieceMode.asStateFlow()

    val _silenced = MutableStateFlow(false)
    val silenced = _silenced.asStateFlow()

    val _micMuted = MutableStateFlow(false)
    val micMuted = _micMuted.asStateFlow()

    // Celestial UI (HUD voice-screen): a UI-choice toggle, not a voice behavior —
    // persisted in SettingsManager, exposed as a StateFlow so the main screen can
    // branch between the classic body and the HUD body reactively. The literal
    // initial below is only a placeholder: loadSettings() runs synchronously in
    // onCreate, before onBind can hand the binder to the Activity, and overwrites
    // it from prefs — whose default is now ON for a fresh install, and always the
    // explicitly saved value on a device that has one.
    val _celestialUi = MutableStateFlow(false)
    val celestialUi = _celestialUi.asStateFlow()

    fun setCelestialUi(enabled: Boolean) {
        _celestialUi.value = enabled
        settingsManager.saveCelestialUi(enabled)
    }

    // Adaptive Theme (Material You): base colors follow the wallpaper, while each
    // persona's themeColor keeps acting as the accent on top. Same UI-choice pattern
    // as celestialUi — persisted in SettingsManager, exposed as a StateFlow so the
    // root theme re-derives the moment the Settings switch flips. Default OFF.
    val _adaptiveTheme = MutableStateFlow(false)
    val adaptiveTheme = _adaptiveTheme.asStateFlow()

    fun setAdaptiveTheme(enabled: Boolean) {
        _adaptiveTheme.value = enabled
        settingsManager.saveAdaptiveTheme(enabled)
    }

    val _chatFontSize = MutableStateFlow(14f)
    val chatFontSize = _chatFontSize.asStateFlow()

    fun setChatFontSize(size: Float) {
        _chatFontSize.value = size
        settingsManager.saveChatFontSize(size)
    }

    internal val _serverBases = MutableStateFlow<List<ServerConfig>>(emptyList())
    val serverBases = _serverBases.asStateFlow()

    internal val _cloudApis = MutableStateFlow<List<CloudApiSetting>>(emptyList())
    val cloudApis = _cloudApis.asStateFlow()

    internal val _customCloudApis = MutableStateFlow<List<CloudApiSetting>>(emptyList())
    val customCloudApis = _customCloudApis.asStateFlow()

    internal val _ollamaBaseUrls = MutableStateFlow<List<ServerConfig>>(emptyList())
    val ollamaBaseUrls = _ollamaBaseUrls.asStateFlow()

    internal val _imageGenBases = MutableStateFlow<List<ServerConfig>>(emptyList())
    val imageGenBases = _imageGenBases.asStateFlow()

    /**
     * Which image backend the app generates with — the switch on the Image Gen screen.
     *
     * A service-level flow rather than a per-persona choice, because the failure it fixes was
     * exactly that the backend depended on where a persona happened to point. Defaults to
     * [DEFAULT_IMAGE_GEN_BACKEND], so an install that never touches the switch behaves as before.
     */
    internal val _activeImageGenBackend = MutableStateFlow(DEFAULT_IMAGE_GEN_BACKEND)
    val activeImageGenBackend = _activeImageGenBackend.asStateFlow()

    private val _manualModels = MutableStateFlow<List<String>>(emptyList())
    val manualModels = _manualModels.asStateFlow()

    internal val _fetchedLocalModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val fetchedLocalModels = _fetchedLocalModels.asStateFlow()

    internal val _fetchedCloudModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val fetchedCloudModels = _fetchedCloudModels.asStateFlow()

    internal val _serverStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverStatus = _serverStatus.asStateFlow()

    internal val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas = _personas.asStateFlow()

    /**
     * The active persona, DERIVED from [currentPersonaName] and [personas] — the single value
     * every consumer observes instead of mirroring.
     *
     * The join itself is [resolveCurrentPersona], so it exists once. `distinctUntilChanged`
     * keeps an edit to an unrelated persona (the editor republishes the whole list on every
     * keystroke) from producing a new emission: what matters is whether the RESOLVED persona
     * changed, not whether the list did.
     *
     * `SharingStarted.Eagerly` rather than `WhileSubscribed`: the value has to be current
     * whether or not anyone is collecting, because a late subscriber (the UI after a rotation)
     * must not see a stale or null selection, and because it makes `.value` readable in tests
     * without a collector. The cost is one coroutine on the existing [serviceScope] for the
     * service's lifetime.
     *
     * Null is a real state — "no persona selected" — so this stays nullable rather than
     * inventing a fallback; picking a substitute persona is a policy decision (see
     * [resolveCurrentPersona]).
     *
     * Declared after [serviceScope] and [_personas] because its initialiser starts collecting
     * at construction.
     */
    val currentPersonaFlow: StateFlow<Persona?> =
        combine(_currentPersonaName, _personas) { name, list -> resolveCurrentPersona(name, list) }
            .distinctUntilChanged()
            .stateIn(serviceScope, SharingStarted.Eagerly, null)

    // ---------------------------------------------------------------- Skills
    // The shared, persona-independent instruction library. Deliberately ONE list every
    // persona references by id (Persona.enabledSkillIds), exactly like _serverBases is
    // referenced by a persona's backendUrl — a skill is authored once and edited once, and
    // every persona that enabled it sees the change at the next turn. Nothing is copied per
    // persona, so there is no fan-out write to keep in step.
    internal val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills = _skills.asStateFlow()

    // ---------------------------------------------------------------- Rooms
    // Room state is deliberately PARALLEL to, never merged into, the single-chat state
    // above: a room has its own identity, its own transcript, and its own round state,
    // and none of it may leak into a persona's individual history (or vice versa).
    //
    //  - _roomMessages holds the ACTIVE room's transcript only; other rooms load on
    //    entry (mirroring switchPersona), so switching rooms does not deserialize
    //    every room's history into memory.
    //  - roomHistoryIoJob is a SEPARATE IO lane from the single-chat historyIoJob.
    //    Room history is keyed by room UUID and owned by no persona, so there is no
    //    messagesOwnerName-equivalent bookkeeping to do — but it must still serialize
    //    its own writes, hence its own queue.
    internal val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms = _rooms.asStateFlow()

    internal val _roomMessages = MutableStateFlow<List<RoomMessage>>(emptyList())
    val roomMessages = _roomMessages.asStateFlow()

    /** The room currently open, or null when the user is in single-chat mode. */
    internal val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId = _activeRoomId.asStateFlow()

    val activeRoom: Room? get() = _activeRoomId.value?.let { id -> _rooms.value.find { it.id == id } }

    /** Non-null exactly while a room round is running; drives the Stop button. */
    internal val _roomRound = MutableStateFlow<RoomRoundState?>(null)
    val roomRound = _roomRound.asStateFlow()

    // Phase 2 (round ownership): the generation that currently OWNS `_roomRound`, and the
    // job running that round's loop. `_roomRound` is service-global while rooms are
    // switchable, so a round that was superseded mid-flight is still unwinding on its own
    // job when its replacement has already published its state — the same hazard H3 solved
    // for streamed placeholders, with the same answer: record the owner, and let only the
    // recorded owner mutate/clear the shared state (see settleRoomRound). A new round
    // records itself BEFORE the old one can observe the change, so the old round's late
    // settle is refused rather than allowed to blank its replacement's round state.
    internal var roomRoundGeneration: Long? = null
    internal var roomRoundJob: Job? = null

    // The room whose transcript _roomMessages currently holds, and the room-history IO
    // queue. Same settle-before-read discipline as messagesOwnerName/historyIoJob: a swap
    // snapshots the outgoing transcript synchronously (before the id moves), writes it in
    // the chained job, and only then loads the incoming one.
    internal var roomMessagesOwnerId: String? = null
    internal var roomHistoryLoadSeq: Long = 0
    internal var roomHistoryIoJob: Job? = null

    internal suspend fun awaitRoomHistoryIo() {
        roomHistoryIoJob?.join()
    }

    private val _favoriteModels = MutableStateFlow<List<String>>(emptyList())
    val favoriteModels = _favoriteModels.asStateFlow()

    internal val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels = _isLoadingModels.asStateFlow()

    /**
     * Model catalog per server URL, for the models screen (step 1). Keyed by URL
     * deliberately: that is the one server identifier already unique-enforced (name is
     * not), so it survives a rename without a migration.
     *
     * Runs BESIDE the existing `_fetchedLocalModels`/`availableModels` pair, which stay
     * exactly as they are; step 4 retires those in favour of this.
     */
    internal val _modelCatalog = MutableStateFlow<Map<String, ModelCatalogState>>(emptyMap())
    internal val modelCatalog = _modelCatalog.asStateFlow()

    // B3 (chat): concurrent model fetches must not let one finishing call leave
    // _isLoadingModels=false while another is still in flight. A single boolean can't
    // represent that; track the number of in-flight fetches instead and only clear the
    // flag when the count returns to zero. The counter is only ever touched on the
    // Main dispatcher: increments run on the callers' thread (always Main), and both
    // fetchers decrement inside withContext(Dispatchers.Main) — fetchModels in its
    // completion block, fetchCloudModels in its finally (which would otherwise run on
    // Dispatchers.IO). That Main confinement is the thread-safety contract for this
    // plain Int — no lock/atomic needed. Hard floor at 0 so a defensive decrement can
    // never go negative.
    @Volatile
    private var modelFetchInFlightCount = 0

    internal fun incrementModelFetchCount() {
        modelFetchInFlightCount++
        _isLoadingModels.value = modelFetchInFlightCount > 0
    }

    internal fun decrementModelFetchCount() {
        modelFetchInFlightCount = (modelFetchInFlightCount - 1).coerceAtLeast(0)
        _isLoadingModels.value = modelFetchInFlightCount > 0
    }

    val _lastPriceSyncTimestamp = MutableStateFlow(0L)
    val lastPriceSyncTimestamp = _lastPriceSyncTimestamp.asStateFlow()

    internal val _pendingCertApproval = MutableStateFlow<CertApprovalRequest?>(null)
    val pendingCertApproval = _pendingCertApproval.asStateFlow()

    // Update-check result (see checkForAppUpdate): non-null only while a newer
    // version is available AND the user hasn't dismissed that specific version.
    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable = _updateAvailable.asStateFlow()

    private val _handsFreeMode = MutableStateFlow(false)
    val handsFreeMode = _handsFreeMode.asStateFlow()

    lateinit var tts: TextToSpeech
    var ttsReady = false
    // DCL visibility (review): @Volatile is REQUIRED here — the espeakEngine getter's
    // fast path reads this field outside espeakLock, so without it a racing thread
    // could see a non-null but stale/partially-constructed reference on multi-core
    // (DCL without @Volatile on the backing field is the classic broken form).
    @Volatile
    private var _espeakEngine: EspeakEngine? = null
    // espeak-ng keeps its state in process-global natives, so EspeakEngine construction
    // must be exactly-once: two concurrent constructors (e.g. the warm-up racing a first
    // playback, or the two warm-up entry points firing within the same onCreate) call
    // espeak_Initialize twice on different threads and the native side aborts with
    // "pthread_mutex_lock called on a destroyed mutex" (observed on device). The fast
    // path stays lock-free; the lock is only ever held while constructing/releasing —
    // synthesis runs on the returned reference outside any lock, as before.
    private val espeakLock = Any()

    val espeakEngine: EspeakEngine?
        get() {
            _espeakEngine?.let { return it }
            synchronized(espeakLock) {
                _espeakEngine?.let { return it }
                return try {
                    EspeakEngine(this).also { _espeakEngine = it }
                } catch (e: Throwable) {
                    android.util.Log.e("AssistantService", "Failed to init eSpeak NG: ${e.message}")
                    null
                }
            }
        }

    // Proactively builds the eSpeak engine on Dispatchers.IO so the first BUNDLED_ESPEAK
    // playback never pays EspeakEngine construction on Main (native lib load, the
    // first-launch ~13MB/439-file espeak-ng-data asset copy, and espeak_Initialize —
    // historically a noticeable stall on the first playResponse -> speakWithEspeak of
    // each session). No-op unless at least one configured persona actually voices
    // through BUNDLED_ESPEAK, so SYSTEM_TTS/GATEWAY/NONE-only setups pay nothing.
    // Idempotent: construction happens at most once per service instance (the getter
    // backs onto _espeakEngine), and a failed construction keeps returning null exactly
    // as before — playback then bails with the existing "engine unavailable" path.
    internal fun warmUpEspeakEngine() {
        if (_espeakEngine != null) return
        if (_personas.value.none { it.voiceMode == VoiceMode.BUNDLED_ESPEAK }) return
        serviceScope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            if (espeakEngine != null) {
                android.util.Log.i(
                    "AssistantService",
                    "eSpeak engine warmed up in ${System.currentTimeMillis() - start}ms"
                )
            }
        }
    }

    // ---- Update check (once per day, silent on any failure) ----------------------------

    // Runs on serviceScope/IO at service start. Throttled by a last-check timestamp in
    // SettingsManager so GitHub's API is hit at most ~once a day regardless of how
    // often the app launches. Uses standardClient — GitHub is genuine third-party
    // infrastructure with CA-validated TLS, unlike the self-hosted gateways that use
    // `client`. Failures (offline, GitHub down, rate-limited) are swallowed by design:
    // this is a nice-to-have and must never surface an error to the user.
    private fun checkForAppUpdate() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                if (now - settingsManager.getLastUpdateCheckTimestamp() < UPDATE_CHECK_INTERVAL_MS) {
                    if (BuildConfig.DEBUG) android.util.Log.d("AssistantService", "Update check skipped (checked within the last 24h)")
                    return@launch
                }
                // Saved before the request: the daily cap holds even when the call
                // fails, so an offline launch never retries until tomorrow.
                settingsManager.saveLastUpdateCheckTimestamp(now)

                val request = Request.Builder()
                    .url("https://api.github.com/repos/nickthegreekk/VoiceAssistant/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                standardClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (BuildConfig.DEBUG) android.util.Log.d("AssistantService", "Update check: HTTP ${response.code}")
                        return@launch
                    }
                    val json = JSONObject(response.body.string())
                    val tag = json.optString("tag_name").trim()
                    val releaseUrl = json.optString("html_url").trim()
                    if (tag.isEmpty() || releaseUrl.isEmpty()) return@launch

                    val latest = tag.removePrefix("v").removePrefix("V")
                    if (!isNewerVersion(latest, BuildConfig.VERSION_NAME)) return@launch
                    // Dismissal memory: a dismissed TAG stays hidden until a NEWER
                    // version is published (keyed on the raw tag, so a future "v"-prefixed
                    // tag counts as a different release).
                    if (settingsManager.getDismissedUpdateVersion() == tag) return@launch

                    _updateAvailable.value = UpdateInfo(latest, releaseUrl)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent by design (nice-to-have check); debug-only log for diagnosability.
                if (BuildConfig.DEBUG) android.util.Log.d("AssistantService", "Update check failed: ${e.message}")
            }
        }
    }

    // Proper numeric comparison, not string equality: 1.0.10 > 1.0.9, "v"/"V" prefixes
    // and "-beta"-style suffixes are tolerated (non-numeric segments count as 0).
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun segments(version: String): List<Int> =
            version.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .split('.')
                .map { seg -> seg.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Remembers the dismissed release tag and hides the banner for this session. */
    fun dismissUpdate(version: String) {
        settingsManager.saveDismissedUpdateVersion(version)
        _updateAvailable.value = null
    }

    lateinit var audioManager: AudioManager
    var audioFocusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false

    // A4 test seam: the framework's AUDIOFOCUS_REQUEST_DELAYED result cannot be reproduced on
    // a physical device. It is returned only while the top of the audio-focus stack is a
    // LOCKED owner — the telephony voice-comm client during ringing / an off-hook call, or a
    // system app's AUDIOFOCUS_FLAG_LOCK request (MediaFocusControl.canReassignAudioFocus()) —
    // and neither can be fabricated here (there is no public API to inject a call, and
    // AUDIOFOCUS_FLAG_LOCK is refused to non-system callers). The overridable function
    // therefore IS the one framework call the DELAYED retry loop re-issues; its default is
    // exactly the previous direct `audioManager.requestAudioFocus(request)`, so production is
    // unchanged and never writes this. Tests restore the captured default in teardown — a
    // stale override would pin every later focus request in the same process to a scripted
    // result.
    internal var audioFocusRequester: (AudioFocusRequest) -> Int = { audioManager.requestAudioFocus(it) }

    // -----------------------------------------------------------------------
    // Proximity barge-in (SPEAKING only): waving a hand over the phone during
    // playback interrupts it via the SAME mechanism as the physical STOP
    // button — stopEverything() — just gesture-triggered instead of tapped.
    //
    // Lifecycle: the state watcher below registers the listener exactly when
    // _state enters SPEAKING and unregisters on leaving it (natural completion,
    // Stop, or the gesture itself — any path that leaves SPEAKING), so the
    // sensor is never listened to outside actual playback.
    //
    // Debounce: a single near reading arms a 200ms confirmation on the main
    // handler. A FAR reading arriving inside that window (a phone being set
    // down / picked up, a hand passing through in a fraction of a second)
    // disarms it — only a sustained hover (near held for 200ms with no far
    // transition) triggers. 200ms was chosen as the balance point: deliberate
    // hand-over gestures naturally exceed it by a wide margin, while
    // incidental passes (a wave past the phone, handling the device) typically
    // complete in well under 150ms. It is also imperceptible as barge-in
    // latency.
    //
    // Near detection: proximity hardware is one of two families — binary
    // (reports 0 = near, maximumRange = far) or short-range distance in cm
    // (0..5..8cm max). A threshold at half the sensor's maximum range classifies
    // "near" correctly for both families and both wave-over and hover gestures.
    //
    // Graceful degradation: no TYPE_PROXIMITY sensor (getDefaultSensor null) →
    // never registered, logged once, zero effect — the feature simply doesn't
    // exist on that device.
    //
    // Call guard: the trigger is skipped when the phone is RINGING or OFFHOOK
    // (checked at trigger time via the permission-free coarse callState) —
    // holding the phone to your ear for a real call must not fire the gesture.
    // -----------------------------------------------------------------------
    private val sensorManager: SensorManager? by lazy {
        getSystemService(SENSOR_SERVICE) as? SensorManager
    }
    private val telephonyManager: TelephonyManager? by lazy {
        getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
    }
    internal val mainHandler = Handler(Looper.getMainLooper())
    private var proximitySensor: Sensor? = null
    private var proximityRegistered = false
    private var proximityNearArmed = false
    private var proximityUnavailableLogged = false
    // Logged once (not per playback) — the earpiece-mode skip is the expected
    // steady state for users who prefer earpiece routing.
    private var proximityEarpieceSkipLogged = false

    private val proximityArmRunnable = Runnable {
        if (assistantState.value == AssistantState.SPEAKING) {
            registerProximityBargeIn()
        }
    }

    private val proximityConfirmRunnable = Runnable {
        if (!proximityRegistered || !proximityNearArmed) return@Runnable
        if (earpieceMode.value) {
            // Earpiece mode was toggled on after this playback started (the
            // listener was armed under speaker mode). Phone-to-ear is normal
            // usage there — same rule as the registration-time gate.
            android.util.Log.i(
                "ProximityBargeIn",
                "Near reading sustained but earpiece mode is active — skipping trigger"
            )
            disarmProximityConfirm()
            return@Runnable
        }
        if (isPhoneCallActive()) {
            // A sustained near reading during an active/ringing call is most
            // likely the phone against the ear, not a wave-to-interrupt
            // gesture. Skip — and disarm so the next hand movement re-arms
            // naturally rather than triggering the instant the call ends.
            android.util.Log.i(
                "ProximityBargeIn",
                "Near reading sustained but a phone call is active — skipping trigger"
            )
            disarmProximityConfirm()
            return@Runnable
        }
        android.util.Log.i(
            "ProximityBargeIn",
            "Sustained near reading during playback — interrupting via stopEverything()"
        )
        stopEverything()
    }

    // Test seam for the guard below: the OS call state cannot be fabricated on a device
    // (there is no public API to inject a call, and placing a real one is neither
    // scriptable nor acceptable), so the state SOURCE is overridable. Production never
    // writes this — it stays on the default reader, which honours the permission gate.
    // Tests restore the captured default in teardown: a stale override would pin the
    // guard to a constant for every later test in the same process.
    // The property itself is deprecated at API 31+ (in favour of per-subscription readers),
    // but it is the correct API for this point-in-time, subscription-agnostic check — the
    // same deliberate choice the method below documents.
    @Suppress("DEPRECATION")
    internal var callStateReader: () -> Int? = {
        // Only cross the Binder when the permission is actually held. Without it the
        // framework's own check throws SecurityException on every trigger (see below), so
        // denial short-circuits here instead of being exception-driven control flow.
        if (hasPhoneStatePermission()) telephonyManager?.callState else null
    }

    /** True when this app currently holds READ_PHONE_STATE — see isPhoneCallActive(). */
    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    // Point-in-time call check at trigger time, via the non-deprecated
    // TelephonyManager.callState property (API 31+, correct for this project's minSdk 31 —
    // PhoneStateListener is deprecated and CallStateListener is a continuous monitor we
    // don't need).
    //
    // READ_PHONE_STATE is genuinely REQUIRED here; the previous comment on this method
    // claimed the coarse call state needed no permission, which was true before API 31 and
    // is false for this app's targetSdk (37). Traced end to end on Android 13
    // (android-13.0.0_r1):
    //   TelephonyManager.getCallState()               -> delegates to TelecomManager
    //   TelecomManager.getCallState()                 -> ITelecomService.getCallStateUsingPackage()
    //                                                    (catches only RemoteException, so a
    //                                                     SecurityException propagates to us)
    //   TelecomServiceImpl.getCallStateUsingPackage() -> under the compat change
    //       ENABLE_GET_CALL_STATE_PERMISSION_PROTECTION, declared
    //       @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S) and therefore enabled for
    //       this app, calls canReadPhoneState(), which does
    //       enforceCallingOrSelfPermission(READ_PHONE_STATE) and throws
    //       SecurityException("getCallState API requires READ_PHONE_STATE for API version 31+")
    // Consequence before the manifest declaration existed: that exception reached the catch
    // below on EVERY trigger, so every call — ringing or off-hook — was reported as "no
    // call" and this guard could never skip. A hand waved over the phone during a real call
    // therefore stopped the assistant's playback.
    //
    // Permission-denied degradation is deliberate and unchanged: the permission is
    // requested, never required. A user who declines gets `null` from the default reader
    // (the check above), i.e. "no call", and the catch still maps ANY unexpected failure —
    // revoked-at-runtime, dead telephony service, RemoteException — to "no call" as well.
    // The barge-in feature can therefore never crash, block, or misbehave for a user who
    // declines; they simply keep the pre-fix behaviour of having no call-state guard.
    internal fun isPhoneCallActive(): Boolean = try {
        val state = callStateReader()
        val active =
            state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING
        if (active) {
            android.util.Log.d("ProximityBargeIn", "Call state=$state — treating as active call")
        }
        active
    } catch (e: Exception) {
        android.util.Log.d(
            "ProximityBargeIn",
            "Call-state check unavailable (${e.javaClass.simpleName}) — assuming no active call"
        )
        false
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val value = event.values.firstOrNull() ?: return
            val near = value < event.sensor.maximumRange * 0.5f
            if (near) {
                if (!proximityNearArmed) {
                    proximityNearArmed = true
                    mainHandler.postDelayed(proximityConfirmRunnable, PROXIMITY_DEBOUNCE_MS)
                }
            } else {
                disarmProximityConfirm()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerProximityBargeIn() {
        // Earpiece mode: bringing the phone to the ear is the normal way to
        // listen in this routing, so a sustained "near" reading is expected
        // usage, not a wave-to-interrupt gesture. Keep the proximity barge-in
        // disarmed for the whole playback (the Stop button and the wave
        // gesture in speaker mode still cover interrupting).
        if (earpieceMode.value) {
            if (!proximityEarpieceSkipLogged) {
                proximityEarpieceSkipLogged = true
                android.util.Log.i("ProximityBargeIn", "Earpiece mode — proximity barge-in not armed (phone-to-ear is normal usage there)")
            }
            return
        }
        val manager = sensorManager
        if (manager == null) {
            if (!proximityUnavailableLogged) {
                proximityUnavailableLogged = true
                android.util.Log.d("ProximityBargeIn", "SensorManager unavailable — proximity barge-in disabled")
            }
            return
        }
        val sensor = proximitySensor
            ?: manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { proximitySensor = it }
        if (sensor == null) {
            if (!proximityUnavailableLogged) {
                proximityUnavailableLogged = true
                android.util.Log.i("ProximityBargeIn", "No proximity sensor on this device — barge-in disabled (graceful)")
            }
            return
        }
        if (!proximityRegistered) {
            proximityRegistered = true
            manager.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            android.util.Log.d("ProximityBargeIn", "Armed for SPEAKING (maxRange=${sensor.maximumRange})")
        }
    }

    private fun unregisterProximityBargeIn() {
        // Cancel any pending delayed arm so a SPEAKING->exit transition can't
        // arm the listener after the fact (e.g. natural completion during the
        // grace window).
        mainHandler.removeCallbacks(proximityArmRunnable)
        disarmProximityConfirm()
        if (proximityRegistered) {
            proximityRegistered = false
            sensorManager?.unregisterListener(proximityListener)
            android.util.Log.d("ProximityBargeIn", "Disarmed (left SPEAKING)")
        }
    }

    private fun disarmProximityConfirm() {
        if (proximityNearArmed) {
            proximityNearArmed = false
            mainHandler.removeCallbacks(proximityConfirmRunnable)
        }
    }


    val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A8: transient loss — pause Gateway voice (MediaPlayer) for
                // resumption on GAIN, but stop the other two engines cleanly
                // (eSpeak can't be paused, and system TTS must stop too — otherwise
                // they'd keep speaking through an incoming call).
                if (currentPlayer?.isPlaying == true) {
                    currentPlayer?.pause()
                    pausedByFocusLoss = true
                }
                currentAudioTrack?.let {
                    try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } catch (e: Exception) {}
                    it.release()
                }
                currentAudioTrack = null
                tts.stop()
                // Fix #13 part 1: only fall back to IDLE when the state is genuinely a
                // playback state. A transient loss while focus is held by an active
                // recording/processing phase (LISTENING/THINKING) must not reset the
                // state — the recorder/pipeline keeps running and would end up
                // desynced from the UI if an unrelated transient loss IDLE'd it.
                if (_state.value == AssistantState.SPEAKING) {
                    _state.value = AssistantState.IDLE
                }
                // Fix #13 part 2: stopAudio()-style routing cleanup. The
                // IN_COMMUNICATION mode + earpiece communication device set for the
                // (now stopped) playback used to survive the loss and fight the
                // telephony stack (e.g. an incoming call) for the audio path. The
                // GAIN branch below re-establishes routing when a paused playback
                // resumes.
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // A8: permanent loss — stop all three engines and abandon the focus
                // request itself rather than leaving it dangling (a GAIN may never come).
                stopAudio()
                pausedByFocusLoss = false
                // Fix: explicit IDLE fallback, matching #13's transient-loss pattern —
                // this branch was missed when #13 was applied. stopAudio() has released
                // every engine, so the engines' own completion callbacks (eSpeak's
                // AudioTrack marker, MediaPlayer onCompletion, TTS onDone) can never
                // run their cleanup themselves. A SPEAKING state left behind by a
                // background state flip racing this handler must not survive a
                // permanent loss. LISTENING/THINKING stay put, same as #13 part 1:
                // their recorder/pipeline keeps running and must not be desynced
                // from the UI by a focus event.
                if (_state.value == AssistantState.SPEAKING) {
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    currentPlayer?.start()
                    pausedByFocusLoss = false
                    _state.value = AssistantState.SPEAKING
                    // Fix #13 part 2 (continued): the transient-loss branch reset the
                    // audio mode/communication device, so re-establish the routing the
                    // paused playback was using before it was interrupted (mirrors the
                    // GRANTED branch of requestAssistantFocus) — otherwise a resumed
                    // earpiece playback would come out of the speaker.
                    if (earpieceMode.value) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val earpiece = audioManager.availableCommunicationDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                            if (earpiece != null) {
                                audioManager.setCommunicationDevice(earpiece)
                            }
                        }
                    } else {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
                    }
                }
            }
        }
    }

    private var recorder: MediaRecorder? = null
    // internal (not private): stopEverything() in AssistantServiceAudio.kt discards
    // the partial recording file on the Stop-button path (Fix: the mic stayed hot and
    // the recorder leaked when STOP was pressed during an active recording).
    internal var outputFile: File? = null
    var currentPlayer: MediaPlayer? = null

    // M4-adjacent: written on Dispatchers.IO by the chat/gateway flows, cancelled/read
    // on Main by the Stop button — @Volatile guarantees the Main thread sees the
    // in-flight call instead of a stale null (a missed cancel is still harmless —
    // stopEverything()'s generation bump discards the turn — but visibility is free).
    @Volatile
    var currentCall: Call? = null
    var currentAudioTrack: AudioTrack? = null

    // Stage-2 streaming TTS: generation counter for the chunked playback
    // sequence. Bumped by stopAudio() (which is called by Stop, barge-in,
    // and focus-loss). Each chunked playback captures the generation at
    // start; every onCompletion checks it. If it has advanced, the entire
    // sequence is stale and must not continue.
    internal var ttsGeneration: Long = 0
    // Fix #3: monotonic token for reveal-restart requests (see RevealRestartRequest).
    // Bumped by requestRevealRestart() on every non-chunked replay so the UI can
    // consume each request exactly once — replaying the same message twice in a row
    // must re-arm the reveal both times, which an index-only signal couldn't express.
    internal var revealRestartSeq: Long = 0
    // A3: utterance ID issued by the most recent speakTextOnDevice() call. TTS
    // onDone/onError callbacks only run their cleanup when their delivered ID still
    // matches this, so a stale callback from an older utterance can't cut off a
    // newer one that is still speaking.
    var currentUtteranceId: String? = null

    fun toggleSilence() {
        _silenced.value = !_silenced.value
        val silenced = _silenced.value
        // Gateway voice (MediaPlayer): mute in place — preserved for unmuting on toggle-off.
        currentPlayer?.setVolume(if (silenced) 0f else 1f, if (silenced) 0f else 1f)
        // A9: eSpeak (AudioTrack) — same in-place mute intent. AudioTrack.setVolume
        // takes a single float (sets both channels), unlike MediaPlayer's two-param form.
        currentAudioTrack?.setVolume(if (silenced) 0f else 1f)
        // A9: System TTS — Android's TextToSpeech API has no live volume control on an
        // already-started utterance, so stopping is the only clean option. tts.stop()
        // is safe: the utterance's onError fires (delegated to onDone), which runs the
        // standard cleanup path. No stuck state.
        if (silenced) tts.stop()
    }

    fun toggleMicMute() {
        _micMuted.value = !_micMuted.value
        vadRecorder?.muted = _micMuted.value
    }

    internal val client: OkHttpClient by lazy { createUnsafeOkHttpClient() }
    internal val publicClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }
    internal val standardClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    internal val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build() 
    }

    internal fun getDynamicClient(persona: Persona, useStandard: Boolean = persona.isCloud): OkHttpClient {
        // Scale read timeout with maxTokens to accommodate slow hardware on long responses
        // Floor of 120s, scales up for budgets above 1200 tokens
        // Thinking-aware multiplier: when the model genuinely reasons, thinking tokens and
        // answer tokens share num_predict and thinking generation is dramatically slower per
        // token on CPU-bound/partially-offloaded hardware, so a budget calibrated only for
        // normal-speed generation undershoots. The direct-Ollama path is non-streaming
        // (stream:false), so readTimeout is a total wall-clock deadline for prompt-eval plus
        // the whole generation — hence 3x whenever thinking is actually enabled.
        val thinkingMultiplier = if (persona.enableThinking && isKnownThinkingModel(persona.model)) 3L else 1L
        val timeout = maxOf(120L, persona.maxTokens / 10L) * thinkingMultiplier
        val baseClient = if (useStandard) standardClient else client
        return baseClient.newBuilder()
            .readTimeout(timeout, TimeUnit.SECONDS)
            .build()
    }

    inner class AssistantBinder : Binder() {
        fun getService(): AssistantService = this@AssistantService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts.language = java.util.Locale.US
            }
        }

        loadSettings()
        createNotificationChannel()
        syncOpenRouterPricing()

        serviceScope.launch {
            assistantState.collect { state ->
                when (state) {
                    // M2 companion: the hands-free toggle releases the recorder, so this
                    // collector must not resurrect a stopped VAD session. Before M2 the
                    // unconditional IDLE write in stopVadListening() self-suppressed the
                    // resume (a resume() on a released AudioRecord is a no-op too), but
                    // with the ownership check that write is now skipped while a turn is
                    // in flight — so the guard has to live here where the recorder itself
                    // is decided.
                    AssistantState.IDLE -> if (_handsFreeMode.value) vadRecorder?.resume()
                    AssistantState.SPEAKING -> vadRecorder?.pause()
                    else -> {}
                }
                // Proximity barge-in: listen ONLY during actual playback. Any
                // transition out of SPEAKING — natural completion, Stop, or the
                // gesture itself — unregisters immediately.
                if (state == AssistantState.SPEAKING) {
                    // Grace period: arm the sensor only after the playback has
                    // been running a moment - the hand that started it is still
                    // near the screen right now.
                    mainHandler.postDelayed(proximityArmRunnable, PROXIMITY_ARM_DELAY_MS)
                } else {
                    unregisterProximityBargeIn()
                }
            }
        }

        // Periodic Health Check
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                _ollamaBaseUrls.value.forEach { checkServerHealth(it, isGateway = false) }
                _serverBases.value.forEach { checkServerHealth(it, isGateway = true) }
                delay(30000) // Every 30 seconds
            }
        }

        // eSpeak warm-up: build the engine off-Main now (persona list is loaded above by
        // loadSettings()) so the first BUNDLED_ESPEAK playback of this session finds it
        // ready instead of stalling Main on lazy construction. Self-guards inside —
        // see warmUpEspeakEngine().
        warmUpEspeakEngine()

        // Update check: throttled to once per day, silent on any failure.
        checkForAppUpdate()
    }

    fun forceCheckHealth(target: ServerConfig, isGateway: Boolean, isImageGen: Boolean = false) {
        serviceScope.launch(Dispatchers.IO) {
            checkServerHealth(target, isGateway, isImageGen)
        }
    }

    private fun buildAuthorizationHeader(target: ServerConfig): String? {
        return when (target.effectiveAuthType) {
            AuthType.NONE -> null
            AuthType.BASIC -> if (!target.username.isNullOrBlank()) {
                Credentials.basic(target.username, target.password ?: "")
            } else null
            AuthType.API_KEY -> if (!target.apiKey.isNullOrBlank()) {
                "Bearer ${target.apiKey}"
            } else null
        }
    }

    private fun buildHealthCheckUrl(target: ServerConfig, isGateway: Boolean): String {
        val base = target.url.trim().removeSuffix("/")
        return when {
            isGateway -> "$base/"
            else -> {
                var b = base
                if (b.endsWith("/v1")) b = b.removeSuffix("/v1")
                if (b.endsWith("/api")) b = b.removeSuffix("/api")
                "$b/api/tags"
            }
        }
    }

    /**
     * The one place a probe request to a server is built WITH its credentials. Image-gen
     * servers (including Test Connection and the passive health re-check) go through
     * [imageGenRequestBuilder], which resolves the auth header through the shared
     * `effectiveAuthType` helper AND picks the probe endpoint for the entry's backend — A1111's
     * `GET /sdapi/v1/sd-models` or ComfyUI's `GET /system_stats` ([imageGenProbePath]); every
     * other server keeps the health-check URL plus [buildAuthorizationHeader].
     */
    private fun buildServerRequest(target: ServerConfig, isGateway: Boolean, isImageGen: Boolean): Request.Builder {
        if (isImageGen) return imageGenRequestBuilder(target, imageGenProbePath(target.backendType))
        val builder = Request.Builder().url(buildHealthCheckUrl(target, isGateway))
        buildAuthorizationHeader(target)?.let { builder.header("Authorization", it) }
        return builder
    }

    private suspend fun checkServerHealth(target: ServerConfig, isGateway: Boolean, isImageGen: Boolean = false) {

        // #3 (health) fix: this runs on Dispatchers.IO — the 30s periodic sweep below and
        // forceCheckHealth both launch there — but _serverStatus may only be
        // read-modify-written on Main, the contract every other writer relies on
        // (fetchCloudModels/#3 (cloud), fetchModels/B3 (chat)). It used to do that
        // read-modify-write inline, off-Main, which made it the last writer outside the
        // contract: a write landing on Main between its `toMutableMap()` and its
        // assignment (e.g. a provider fetch finishing in that window) was silently
        // dropped, and the clobbered entry stayed wrong until the next 30s tick.
        // So compute the outcome off-Main and apply it through a Main hop, one entry at a
        // time, exactly like the other writers.
        val outcome = try {
            val requestBuilder = buildServerRequest(target, isGateway, isImageGen)

            fastClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful || response.code == 401) {
                    "Online"
                } else {
                    "failed: ${response.code}"
                }
            }
        } catch (e: Exception) {
            "failed: offline"
        }

        withContext(Dispatchers.Main) {
            val statusMap = _serverStatus.value.toMutableMap()
            statusMap[target.url] = outcome
            _serverStatus.value = statusMap
        }
    }

    // Setup-context connection test for the Add/Edit Server dialogs ("Test Connection"
    // button, save flow, and status-dot re-check). Unlike checkServerHealth — which
    // deliberately treats 401 as "Online" for the passive status dot — this returns the
    // raw outcome so the setup UI can surface the specific problem (bad credentials vs
    // wrong URL vs unreachable host). Short 10s timeouts so the Test button fails fast;
    // built on `client` so self-signed gateway certs are accepted exactly as today.
    internal suspend fun testServerConnection(target: ServerConfig, isGateway: Boolean, isImageGen: Boolean = false): ServerConnectionResult {
        val probeClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = buildServerRequest(target, isGateway, isImageGen)
                probeClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        ServerConnectionResult(success = true)
                    } else {
                        // A 404/405 from an image-gen entry's own endpoint is the signature of the
                        // entry sitting in the wrong section (see imageGenBackendMismatchHint): the
                        // address is reachable and running another API. The probe is only sent for
                        // those two statuses, and only for image-gen entries.
                        ServerConnectionResult(
                            success = false,
                            httpCode = response.code,
                            detail = response.message.ifBlank { null },
                            backendMismatch = if (isImageGen) {
                                imageGenBackendMismatchHint(target, response.code)
                            } else null
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerConnectionResult(success = false, httpCode = null, detail = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun loadSettings() {
        settingsManager.getServerBases()?.let { _serverBases.value = it }
        val loadedCloud = settingsManager.getCloudApis() ?: emptyList()
        // Migration: Move "OpenAI-Compatible" (icon "C") to custom list if found in fixed list
        _cloudApis.value = loadedCloud.filter { it.icon != "C" }
        val customFromFixed = loadedCloud.filter { it.icon == "C" }
        val allCustom = (settingsManager.getCustomCloudApis() ?: emptyList()) + customFromFixed
        _customCloudApis.value = allCustom
        
        val loadedPersonas = settingsManager.getPersonas() ?: emptyList()
        _personas.value = loadedPersonas

        // Skills load before the personas are used for anything, because a persona's
        // enabledSkillIds are resolved against this list. A persona referencing a skill that
        // no longer exists is not repaired here — enabledSkillsFor() simply drops the id, so
        // a stale reference can never inject anything or throw.
        _skills.value = settingsManager.getSkills() ?: emptyList()
        
        // If we have personas, initialize the first one as current if not already set
        if (currentPersonaName == null && loadedPersonas.isNotEmpty()) {
            switchPersona(loadedPersonas[0])
        }

        _ollamaBaseUrls.value = settingsManager.getOllamaBases() ?: emptyList()
        // Spontaneous cloud guard, load-time (supplementary). savePersonas already prevents
        // an enabled cloud persona from ever being WRITTEN, and add/updatePersona prevent
        // one being held in memory — this closes the remaining gap for a personas_v2 entry
        // authored outside the app (root/adb, or a hand-restored prefs blob). Runs here
        // because it needs both name sets, which is the first point they are all loaded.
        // Idempotent and equality-conflated, so a clean list re-assigns an equal list and
        // emits nothing.
        _personas.value = _personas.value.map {
            SpontaneousPolicy.sanitize(it, ollamaServerNames(), cloudProviderNames())
        }
        // Spontaneous scheduling (step 5): safety net, deliberately NEVER replacing work
        // that is already pending. WorkManager persists the request across process death
        // and reboot on its own, so re-arming unconditionally here would restart every
        // countdown on each service start (a user opening the app hourly would never see a
        // 4-hour gap elapse) and could REPLACE a run that is mid-turn. What this does catch
        // is personas that reached storage without passing through add/updatePersona — an
        // importBackup being the one that matters — where nothing has armed them at all.
        refreshAllSpontaneousSchedules()
        _imageGenBases.value = settingsManager.getImageGenBases()?.takeIf { it.isNotEmpty() } ?: listOf(ServerConfig("Local A1111", "http://0.0.0.0:7860"))
        // Loaded after the list so the two can never be momentarily inconsistent for a collector.
        _activeImageGenBackend.value = settingsManager.getActiveImageGenBackend()
        // Rooms load AFTER the Ollama/cloud server lists, because eligibility is judged
        // against them: a member whose backing server has since been deleted, or which is
        // a gateway-only/image-generator/translator persona, cannot speak, so it is
        // pruned from the member list on load (the transcript is kept — see loadRooms).
        loadRooms()
        _totalCost.value = settingsManager.getTotalCost()
        _favoriteModels.value = settingsManager.getFavoriteModels() ?: emptyList()
        _lastPriceSyncTimestamp.value = settingsManager.getLastPriceSyncTimestamp()
        _celestialUi.value = settingsManager.getCelestialUi()
        _adaptiveTheme.value = settingsManager.getAdaptiveTheme()
        _chatFontSize.value = settingsManager.getChatFontSize()

        // Fetch models for all enabled cloud providers
        (_cloudApis.value + allCustom).forEach { api ->
            if (api.apiKey.isNotBlank() || api.icon == "C") {
                fetchCloudModels(api)
            }
        }
    }

    // C1: settings/history persistence is debounced and written on Dispatchers.IO.
    // saveSettings() is called on every message append (plus ~30 settings mutators),
    // so rapid calls coalesce into a single disk write SAVE_DEBOUNCE_MS after the
    // last call. The deferred write re-reads the StateFlows at write time, so it
    // always persists the LATEST state (never a stale snapshot). The mutex
    // serializes overlapping writers, and onDestroy() flushes synchronously so a
    // pending debounce window can never lose state on teardown.
    private val saveMutex = Mutex()
    private var saveSettingsJob: Job? = null

    fun saveSettings() {
        saveSettingsJob?.cancel()
        saveSettingsJob = serviceScope.launch(Dispatchers.IO) {
            delay(SAVE_DEBOUNCE_MS)
            saveMutex.withLock { persistSettings() }
        }
    }

    private fun persistSettings() {
        settingsManager.saveServerBases(_serverBases.value)
        settingsManager.saveCloudApis(_cloudApis.value)
        settingsManager.saveCustomCloudApis(_customCloudApis.value)
        // Ollama bases are written BEFORE the personas, deliberately: savePersonas reads
        // the server lists back out of this same store to evaluate the spontaneous cloud
        // guard, so the lists must already reflect this save. (Cloud lists are likewise
        // ahead of it; only ollama needed moving — it previously sat at the end.) Without
        // this, a server added and a persona saved inside one debounce window would be
        // judged against the previous ollama list and could silently strip a valid flag.
        settingsManager.saveOllamaBases(_ollamaBaseUrls.value)
        // The shared skill library rides the same debounced save. Written BEFORE the persona
        // list so a restore/export snapshot always sees skills and referencing personas in a
        // consistent pair, never a persona pointing at a library entry that wasn't saved.
        // (A crash between the two writes would otherwise leave personas referencing skill
        // ids that were never persisted, which the load path would drop as dangling and
        // silently disable every enabled skill.)
        settingsManager.saveSkills(_skills.value)
        settingsManager.savePersonas(_personas.value)
        // Rooms ride the same debounced save as everything else. Their transcripts do
        // NOT — those go through saveRoomHistory(), because a room transcript belongs to
        // no persona and must never be keyed off the single-chat owner bookkeeping below.
        settingsManager.saveRooms(_rooms.value)
        // L5: save the pair that actually belongs together. `currentPersonaName` and
        // `_messages` disagree only while a persona-history swap is in flight (the name has
        // already moved to the incoming persona while the outgoing messages are still on
        // screen), so the recorded owner of the visible messages wins; when no owner was ever
        // recorded the live name is the right key (the unchanged pre-L5 behavior). Keying off
        // the live name here would file one persona's messages under another's name — e.g. a
        // save from onTaskRemoved/onDestroy that lands mid-swap.
        (messagesOwnerName ?: currentPersonaName)?.let { name ->
            settingsManager.savePersonaMessages(name, _messages.value)
        }
        settingsManager.saveImageGenBases(_imageGenBases.value)
        settingsManager.saveActiveImageGenBackend(_activeImageGenBackend.value)
        settingsManager.saveTotalCost(_totalCost.value)
        settingsManager.saveFavoriteModels(_favoriteModels.value)
    }

    fun addServerBase(name: String, url: String, username: String? = null, password: String? = null, authType: AuthType = AuthType.NONE, apiKey: String? = null) {
        if (_serverBases.value.none { it.url == url }) {
            _serverBases.value = _serverBases.value + ServerConfig(name, url, username, password, authType, apiKey)
            saveSettings()
            fetchModels()
        } else {
            // Fix #6 (parity with the update paths): the duplicate was already rejected
            // here, but silently — surface it the same way updateServerBase does.
            val existing = _serverBases.value.first { it.url == url }
            Toast.makeText(
                applicationContext,
                "URL already used by server '${existing.name}' — add rejected",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun removeServerBase(config: ServerConfig) {
        _serverBases.value = _serverBases.value - config
        saveSettings()
    }

    fun updateServerBase(oldConfig: ServerConfig, newName: String, newUrl: String, newUsername: String? = null, newPassword: String? = null, newAuthType: AuthType = AuthType.NONE, newApiKey: String? = null) {
        val current = _serverBases.value.toMutableList()
        val idx = current.indexOf(oldConfig)
        if (idx != -1) {
            // Fix #6: the add path rejects duplicate URLs (none { it.url == url }), but this
            // update path skipped the check — editing an entry's URL to one another entry
            // already owned silently created a duplicate. Reject the edit instead and say
            // why via toast (the entry being edited is excluded from the check, so saving
            // with an unchanged URL — name/credential edits only — still works).
            val duplicate = current.withIndex().firstOrNull { (i, cfg) -> i != idx && cfg.url == newUrl }
            if (duplicate != null) {
                Toast.makeText(
                    applicationContext,
                    "URL already used by server '${duplicate.value.name}' — edit rejected",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            current[idx] = ServerConfig(newName, newUrl, newUsername, newPassword, newAuthType, newApiKey)
            _serverBases.value = current
            // A persona addresses its gateway by URL too (backendUrl = gw.url), so an edited URL
            // must repoint the gateway/translator personas that named it — the same chokepoint as
            // the Ollama rename migration. Image-generator personas are deliberately skipped: their
            // backendUrl points at an image-gen server, which may share the host but is a different
            // registry.
            val oldUrl = oldConfig.url
            if (newUrl != oldUrl) {
                _personas.value = rewritePersonasBackendUrl(_personas.value, oldUrl, newUrl, forImageGenerators = false)
            }
            saveSettings()
            fetchModels(current[idx])
        }
    }

    fun toggleFavoriteModel(model: String) {
        if (_favoriteModels.value.contains(model)) {
            _favoriteModels.value = _favoriteModels.value - model
        } else {
            _favoriteModels.value = _favoriteModels.value + model
        }
        saveSettings()
    }

    /**
     * Every name a `"[Name] model"` string could currently mean: Ollama servers plus BOTH
     * cloud lists.
     *
     * Cloud providers are included deliberately. `PersonaRouting` decides cloud-vs-Ollama
     * by looking the same prefix string up in two different name sets, so a local server
     * named "OpenAI" would be indistinguishable from the OpenAI provider and would
     * silently take its personas. See ServerIdentity.kt.
     */
    private fun allServerAndProviderNames(): List<String> =
        _ollamaBaseUrls.value.map { it.name } +
            _cloudApis.value.map { it.name } +
            _customCloudApis.value.map { it.name }

    /** The toast text for a name collision, or null when the name is free. */
    private fun nameConflictMessage(
        candidate: String,
        exclude: Collection<String> = emptyList()
    ): String? {
        if (!isServerNameTaken(candidate, allServerAndProviderNames(), exclude)) return null
        return "'${candidate.trim()}' already used by another server or provider — not saved"
    }

    fun addOllamaBase(name: String, url: String, username: String? = null, password: String? = null, authType: AuthType = AuthType.NONE, apiKey: String? = null) {
        // Name first: a duplicate name makes every "[Name] model" string ambiguous, which is
        // strictly worse than a duplicate URL, and a URL clash is rejected below anyway.
        val conflict = nameConflictMessage(name)
        if (conflict != null) {
            Log.w("AssistantService", "addOllamaBase refused: $conflict")
            Toast.makeText(applicationContext, conflict, Toast.LENGTH_LONG).show()
            return
        }
        if (_ollamaBaseUrls.value.none { it.url == url }) {
            _ollamaBaseUrls.value = _ollamaBaseUrls.value + ServerConfig(name, url, username, password, authType, apiKey)
            saveSettings()
            fetchModels()
        } else {
            // Fix #6 (parity with the update paths): the duplicate was already rejected
            // here, but silently — surface it the same way updateOllamaBase does.
            val existing = _ollamaBaseUrls.value.first { it.url == url }
            Toast.makeText(
                applicationContext,
                "URL already used by Ollama server '${existing.name}' — add rejected",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun removeOllamaBase(config: ServerConfig) {
        _ollamaBaseUrls.value = _ollamaBaseUrls.value - config
        // Drop this server's favorites: a "[Name] model" favorite for a server that no
        // longer exists can never be shown or toggled again, and would sit in storage
        // forever. Personas are deliberately NOT touched — see the note in the plan: a
        // persona pointing at a deleted server keeps its string and shows the existing
        // error, which is this step's "leave that behaviour alone".
        val kept = _favoriteModels.value.filterNot { serverPrefixOf(it) == config.name }
        if (kept.size != _favoriteModels.value.size) {
            _favoriteModels.value = kept
        }
        dropModelCatalog(config.url)
        saveSettings()
    }

    fun moveServerUp(config: ServerConfig, isOllama: Boolean) {
        val list = if (isOllama) _ollamaBaseUrls.value.toMutableList() else _serverBases.value.toMutableList()
        val idx = list.indexOf(config)
        if (idx > 0) {
            val temp = list[idx]
            list[idx] = list[idx - 1]
            list[idx - 1] = temp
            if (isOllama) _ollamaBaseUrls.value = list else _serverBases.value = list
            saveSettings()
        }
    }

    fun moveServerDown(config: ServerConfig, isOllama: Boolean) {
        val list = if (isOllama) _ollamaBaseUrls.value.toMutableList() else _serverBases.value.toMutableList()
        val idx = list.indexOf(config)
        if (idx != -1 && idx < list.size - 1) {
            val temp = list[idx]
            list[idx] = list[idx + 1]
            list[idx + 1] = temp
            if (isOllama) _ollamaBaseUrls.value = list else _serverBases.value = list
            saveSettings()
        }
    }

    fun updateOllamaBase(oldConfig: ServerConfig, newName: String, newUrl: String, newUsername: String? = null, newPassword: String? = null, newAuthType: AuthType = AuthType.NONE, newApiKey: String? = null) {
        val current = _ollamaBaseUrls.value.toMutableList()
        val idx = current.indexOf(oldConfig)
        if (idx != -1) {
            val oldUrl = current[idx].url
            // Fix #6: same dedup the add path enforces (none { it.url == url }) — see
            // updateServerBase for the full rationale. The edited entry is excluded,
            // so saving with an unchanged URL still works; a URL another entry owns
            // is rejected with a toast instead of creating a silent duplicate.
            val duplicate = current.withIndex().firstOrNull { (i, cfg) -> i != idx && cfg.url == newUrl }
            if (duplicate != null) {
                Toast.makeText(
                    applicationContext,
                    "URL already used by Ollama server '${duplicate.value.name}' — edit rejected",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            // Name uniqueness, checked against every name a "[Name] model" string could
            // mean (Ollama servers plus both cloud lists). The entry being edited is
            // excluded, so saving without changing the name is not a self-collision, and
            // renaming one member of a pre-existing duplicate pair out is allowed.
            val oldName = current[idx].name
            val conflict = nameConflictMessage(newName, exclude = listOf(oldName))
            if (conflict != null) {
                Log.w("AssistantService", "updateOllamaBase refused: $conflict")
                Toast.makeText(applicationContext, conflict, Toast.LENGTH_LONG).show()
                return
            }

            current[idx] = ServerConfig(newName, newUrl, newUsername, newPassword, newAuthType, newApiKey)
            _ollamaBaseUrls.value = current

            // Rename migration, BEFORE the save below. A persona left pointing at the old
            // name reads as an UNCONFIGURED server, and SpontaneousPolicy.sanitize then
            // strips its spontaneousEnabled on the very next persist — a rename that
            // silently turns off a feature the user never touched.
            if (newName != oldName) {
                migrateServerNameReferences(oldName, newName)
            }

            // The catalog cache is keyed by URL, so only a URL change invalidates it; a
            // name-only edit keeps the models already fetched.
            if (newUrl != oldUrl) {
                dropModelCatalog(oldUrl)
            }

            saveSettings()
            fetchModels(current[idx])
        }
    }

    /**
     * Repoint everything that stores an Ollama server by NAME at its new name.
     *
     * Audited for other holders of a server name or a "[server] model" string:
     *  - [Persona.model] — rewritten here.
     *  - `favorite_models_v2` — rewritten here.
     *  - [Persona.backendUrl] — a URL, not a name, and untouched by a rename; an explicit
     *    backendUrl is the user's own override, not the resolved server.
     *  - [Room.memberNames] — PERSONA names, not server names. Nothing to do.
     *  - [Skill] — has no server or model field at all. Nothing to do.
     *  - `persona_messages_<name>` and SpontaneousState — keyed by PERSONA name, which is
     *    also why the armed WorkManager chain (keyed by persona name) is unaffected by a
     *    server rename.
     *  - Gateway and image-gen registries — addressed through `backendUrl`, not the
     *    "[Name] model" prefix. Out of scope here.
     *
     * Order matters: the persona list is rewritten in memory FIRST, so the `saveSettings()`
     * that follows persists the new strings and no later save can put the old ones back.
     */
    private fun migrateServerNameReferences(oldName: String, newName: String) {
        settingsManager.migrateFavoriteModelServerPrefix(oldName, newName)
        _favoriteModels.value = rewriteServerPrefixes(_favoriteModels.value, oldName, newName)
        _personas.value = rewritePersonasServerPrefix(_personas.value, oldName, newName)
    }

    /**
     * Switches the generator. The first entry of the new type becomes the one image requests use,
     * so this is the whole of the "which backend" decision — no per-entry flags.
     */
    fun setActiveImageGenBackend(backend: ImageGenBackend) {
        if (_activeImageGenBackend.value == backend) return
        _activeImageGenBackend.value = backend
        saveSettings()
    }

    fun addImageGenBase(name: String, url: String, username: String? = null, password: String? = null, authType: AuthType = AuthType.NONE, apiKey: String? = null, backendType: ImageGenBackend = DEFAULT_IMAGE_GEN_BACKEND, comfyWorkflow: String? = null) {
        if (_imageGenBases.value.none { it.url == url }) {
            _imageGenBases.value = _imageGenBases.value + ServerConfig(name, url, username, password, authType, apiKey, backendType, comfyWorkflow)
            saveSettings()
        } else {
            val existing = _imageGenBases.value.first { it.url == url }
            Toast.makeText(
                applicationContext,
                "URL already used by Image Gen server '${existing.name}' — add rejected",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun removeImageGenBase(config: ServerConfig) {
        _imageGenBases.value = _imageGenBases.value - config
        saveSettings()
    }

    fun updateImageGenBase(oldConfig: ServerConfig, newName: String, newUrl: String, newUsername: String? = null, newPassword: String? = null, newAuthType: AuthType = AuthType.NONE, newApiKey: String? = null, newBackendType: ImageGenBackend = oldConfig.backendType, newComfyWorkflow: String? = oldConfig.comfyWorkflow) {
        val current = _imageGenBases.value.toMutableList()
        val idx = current.indexOf(oldConfig)
        if (idx != -1) {
            val duplicate = current.withIndex().firstOrNull { (i, cfg) -> i != idx && cfg.url == newUrl }
            if (duplicate != null) {
                Toast.makeText(
                    applicationContext,
                    "URL already used by Image Gen server '${duplicate.value.name}' — edit rejected",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            // The backend type and the workflow default to the entry's existing values, so an edit
            // dialog that does not mention them (or a caller that only changes the URL) can never
            // silently reset a ComfyUI entry back to A1111 or drop its template.
            current[idx] = ServerConfig(newName, newUrl, newUsername, newPassword, newAuthType, newApiKey, newBackendType, newComfyWorkflow)
            _imageGenBases.value = current
            // A persona references its image-gen server by URL (backendUrl = cfg.url), so an
            // edited URL must repoint every image-generator persona at the new address — the
            // same chokepoint as the Ollama rename migration. Without it the persona keeps
            // pointing at the old address, which no entry matches any more.
            val oldUrl = oldConfig.url
            if (newUrl != oldUrl) {
                _personas.value = rewritePersonasBackendUrl(_personas.value, oldUrl, newUrl, forImageGenerators = true)
            }
            saveSettings()
        }
    }

    fun moveImageGenUp(config: ServerConfig) {
        val list = _imageGenBases.value.toMutableList()
        val idx = list.indexOf(config)
        if (idx > 0) {
            val temp = list[idx]
            list[idx] = list[idx - 1]
            list[idx - 1] = temp
            _imageGenBases.value = list
            saveSettings()
        }
    }

    fun moveImageGenDown(config: ServerConfig) {
        val list = _imageGenBases.value.toMutableList()
        val idx = list.indexOf(config)
        if (idx != -1 && idx < list.size - 1) {
            val temp = list[idx]
            list[idx] = list[idx + 1]
            list[idx + 1] = temp
            _imageGenBases.value = list
            saveSettings()
        }
    }

    fun updateCloudApi(index: Int, api: CloudApiSetting) {
        val current = _cloudApis.value.toMutableList()
        if (index in current.indices) {
            current[index] = api
        } else {
            current.add(api)
        }
        _cloudApis.value = current
        saveSettings()
    }

    fun moveCloudApiUp(api: CloudApiSetting) {
        val list = _cloudApis.value.toMutableList()
        val idx = list.indexOf(api)
        if (idx > 0) {
            val temp = list[idx]
            list[idx] = list[idx - 1]
            list[idx - 1] = temp
            _cloudApis.value = list
            saveSettings()
        }
    }

    fun moveCloudApiDown(api: CloudApiSetting) {
        val list = _cloudApis.value.toMutableList()
        val idx = list.indexOf(api)
        if (idx != -1 && idx < list.size - 1) {
            val temp = list[idx]
            list[idx] = list[idx + 1]
            list[idx + 1] = temp
            _cloudApis.value = list
            saveSettings()
        }
    }

    fun addCustomCloudApi(name: String, url: String, apiKey: String) {
        val newApi = CloudApiSetting(name, url, apiKey, "C", Color(0xFF808080), isEditableUrl = true)
        _customCloudApis.value = _customCloudApis.value + newApi
        saveSettings()
        fetchCloudModels(newApi)
    }

    fun removeCustomCloudApi(api: CloudApiSetting) {
        _customCloudApis.value = _customCloudApis.value - api
        saveSettings()
    }

    fun updateCustomCloudApi(oldApi: CloudApiSetting, newApi: CloudApiSetting) {
        val current = _customCloudApis.value.toMutableList()
        val idx = current.indexOf(oldApi)
        if (idx != -1) {
            current[idx] = newApi
            _customCloudApis.value = current
            saveSettings()
            fetchCloudModels(newApi)
        }
    }

    fun moveCustomCloudApiUp(api: CloudApiSetting) {
        val list = _customCloudApis.value.toMutableList()
        val idx = list.indexOf(api)
        if (idx > 0) {
            val temp = list[idx]
            list[idx] = list[idx - 1]
            list[idx - 1] = temp
            _customCloudApis.value = list
            saveSettings()
        }
    }

    fun moveCustomCloudApiDown(api: CloudApiSetting) {
        val list = _customCloudApis.value.toMutableList()
        val idx = list.indexOf(api)
        if (idx != -1 && idx < list.size - 1) {
            val temp = list[idx]
            list[idx] = list[idx + 1]
            list[idx + 1] = temp
            _customCloudApis.value = list
            saveSettings()
        }
    }

    fun addPersona(persona: Persona) {
        // Name uniqueness, in-memory chokepoint. Together with SettingsManager.savePersonas
        // (which persistSettings reaches below) this means a duplicate name can be neither
        // observed nor stored — not by the persona editor, not by the header button's
        // "add the translator if it's missing", not by the defaults migration, not by a
        // future path that never touches this screen.
        //
        // WHY a hard refusal rather than a cosmetic dedupe or an auto-renamed copy: the
        // name is the persona's identity in every key the app holds, not a label. History is
        // `persona_messages_<name>`, room membership is `memberNames: List<String>`, the
        // spontaneous state map is name-keyed, and the scheduler's WorkManager chain is named
        // after it. Two personas sharing one name would therefore share one history file
        // (each saving over the other's), make room membership ambiguous (removing "X" takes
        // both), and fight over one scheduler chain and one notification id. None of that is
        // repairable by showing the user a friendlier list, so the write is refused at the
        // door and named in the message, exactly like addOllamaBase's duplicate-URL refusal.
        //
        // Exact match, like every other name comparison here (the defaults migration, room
        // membership, the history keys): "Bob" and "Bob " are different keys, so pretending
        // they collide would refuse writes the storage layer would have kept apart.
        val clash = _personas.value.firstOrNull { it.name == persona.name }
        if (clash != null) {
            val message = "'${persona.name}' already exists — persona not added"
            Log.w("AssistantService", "addPersona refused: $message")
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            return
        }
        // Spontaneous cloud guard (plan §6, location 3): in-memory chokepoint. Together
        // with SettingsManager.savePersonas (which persistSettings reaches below) this
        // means an ineligible persona can be neither observed nor stored as enabled.
        val guarded = SpontaneousPolicy.sanitize(persona, ollamaServerNames(), cloudProviderNames())
        _personas.value = _personas.value + guarded
        saveSettings()
        // A new persona may already be enabled (added by a restore, or created from a
        // template), and there is certainly no chain for it yet, so arm it outright.
        refreshSpontaneousSchedule(guarded.name, force = true)
    }

    fun updatePersona(index: Int, persona: Persona) {
        val current = _personas.value.toMutableList()
        if (index in current.indices) {
            val previous = current[index]
            val oldName = previous.name
            if (persona.name != oldName) {
                // A rename ONTO an existing name is the same collision as a duplicate add,
                // and strictly worse: `migratePersonaHistory` below would move this
                // persona's history onto the other one's name key — destroying the victim's
                // chat — and `renameRoomMemberEverywhere` would merge their memberships. So
                // the rename is refused before either runs.
                //
                // The whole save is refused rather than just the name: this is the editor's
                // single commit (it debounces every field into one call), so storing the new
                // fields under the old name is not an option either.
                val takenBy = current.withIndex().firstOrNull { (i, p) -> i != index && p.name == persona.name }
                if (takenBy != null) {
                    val message = "'${persona.name}' already exists — rename refused"
                    Log.w("AssistantService", "updatePersona refused: $message")
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                    return
                }
                // Rename: move the persona's history to the new name-keyed storage
                // entry (it would otherwise be orphaned on disk forever), and when
                // the renamed persona is the active one, keep the selection pointing
                // at it — same in-memory conversation, just a new identity, so no
                // reload and no visible switch.
                // F1: migratePersonaHistory is the SHARED rename surface, so the persona's
                // spontaneous delivery state moves in this same call — lastSentAt and
                // sentTodayCount are PRESERVED under the new name, never reset, or a
                // freshly renamed persona could immediately re-message after its rename.
                settingsManager.migratePersonaHistory(oldName, persona.name)
                // L5: the migration itself stays synchronous — anything that saves right after
                // this rename (persistSettings below) must already see the new key. But a
                // persona-history swap that was already in flight may still be writing this
                // persona's outgoing history, and that write would recreate the very entry the
                // migration just removed. Re-running the migration inside the history-IO lane,
                // after that write, closes the window (safe and idempotent: it moves at most
                // the same snapshot again, and no-ops when the old entry is gone).
                historyIoJob?.let { inFlight ->
                    val renamedFrom = oldName
                    val renamedTo = persona.name
                    historyIoJob = serviceScope.launch {
                        inFlight.join()
                        withContext(Dispatchers.IO) {
                            settingsManager.migratePersonaHistory(renamedFrom, renamedTo)
                        }
                    }
                }
                if (currentPersonaName == oldName) {
                    currentPersonaName = persona.name
                }
                // L5: the in-memory conversation keeps belonging to this persona — it was just
                // migrated to the new name-keyed entry — so the recorded owner must move with
                // it, or the next persistSettings would save it back under the old (now
                // orphaned) key and undo the migration.
                if (messagesOwnerName == oldName) {
                    messagesOwnerName = persona.name
                }
                // Room membership is name-keyed too, so a rename must re-point it —
                // otherwise `loadRooms` would prune the old name out of every room
                // the persona belonged to and the loss would become permanent.
                renameRoomMemberEverywhere(oldName, persona.name)
            }
            // Spontaneous cloud guard (plan §6, location 3): the editor is not the only
            // writer — this is. Sanitized at the moment of storage, so every downstream
            // consumer (persistSettings, the UI's personas flow, the worker's eventual
            // preflight) sees a persona that could actually send. Sanitizing only flips
            // spontaneousEnabled, so the rename/artwork handling above and below is
            // unaffected — those compare name and image paths, never this flag.
            current[index] = SpontaneousPolicy.sanitize(persona, ollamaServerNames(), cloudProviderNames())
            _personas.value = current
            // Spontaneous scheduling (step 5): touching WorkManager is not free — REPLACE
            // restarts the countdown AND cancels any run currently in flight — so it happens
            // only when the save actually changed something the schedule is derived from.
            // A prompt/temperature/artwork edit must leave the timer exactly where it was.
            // Compared against the PREVIOUS stored persona and the SANITIZED new one, so a
            // guard flipping spontaneousEnabled off counts as a change (and disarms).
            if (spontaneousScheduleDiffers(previous, current[index])) {
                // The unique-work key is the persona name, so a rename orphans the old
                // chain — drop it explicitly rather than waiting for its own guard to fire.
                if (oldName != current[index].name) removeSpontaneousSchedule(oldName)
                refreshSpontaneousSchedule(current[index].name, force = true)
            }
            // Artwork lifecycle: a save that REPLACED a slot points the persona at a
            // new file, and a save that CLEARED one points it at null — either way the
            // previously referenced file is now unreachable and would sit in filesDir
            // forever, so it is deleted here. Compared by PATH rather than by Persona
            // equality, because re-saving an unmodified persona must NOT delete its
            // artwork (the editor auto-saves on every keystroke). Filenames are unique
            // per save (SettingsManager.newArtworkFile), so a path is never shared
            // between two personas — nothing else can be pointing at it.
            if (previous.iconImageUri != persona.iconImageUri) {
                settingsManager.deleteArtworkFile(previous.iconImageUri)
            }
            if (previous.backgroundImageUri != persona.backgroundImageUri) {
                settingsManager.deleteArtworkFile(previous.backgroundImageUri)
            }
            saveSettings()
        }
    }

    // ---------------------------------------------------------------- Skills
    // Library edits follow the same mutate-then-saveSettings() shape as the server lists
    // (addServerBase/updateCloudApi/...): the flow is the single source of truth for the UI,
    // and persistence is the existing debounced save, not a bespoke one.
    fun addSkill(skill: Skill) {
        _skills.value = _skills.value + skill
        saveSettings()
    }

    fun updateSkill(index: Int, skill: Skill) {
        val current = _skills.value.toMutableList()
        if (index !in current.indices) return
        // The id is the reference every persona holds, so it is NOT editable: keep the
        // existing one even if the caller passes a different value, exactly as updateCloudApi
        // keeps a stable identity.
        current[index] = skill.copy(id = current[index].id)
        _skills.value = current
        saveSettings()
    }

    /**
     * Delete a skill AND drop its id from every persona that enabled it.
     *
     * Pruning here (rather than only at read time) keeps the persisted data honest: without
     * it a deleted skill leaves permanent dangling ids that every export would carry, and the
     * persona UI would have to render an enabled-but-missing entry forever. This is the same
     * obligation removePersona has toward Room.memberNames.
     */
    fun removeSkill(skill: Skill) {
        _skills.value = _skills.value - skill
        val referencing = _personas.value.filter { skill.id in it.enabledSkillIds }
        if (referencing.isNotEmpty()) {
            _personas.value = _personas.value.map { persona ->
                if (skill.id !in persona.enabledSkillIds) persona
                else persona.copy(enabledSkillIds = persona.enabledSkillIds - skill.id)
            }
        }
        saveSettings()
    }

    fun moveSkillUp(skill: Skill) {
        val list = _skills.value.toMutableList()
        val idx = list.indexOf(skill)
        if (idx > 0) {
            val temp = list[idx]
            list[idx] = list[idx - 1]
            list[idx - 1] = temp
            _skills.value = list
            saveSettings()
        }
    }

    fun moveSkillDown(skill: Skill) {
        val list = _skills.value.toMutableList()
        val idx = list.indexOf(skill)
        if (idx != -1 && idx < list.size - 1) {
            val temp = list[idx]
            list[idx] = list[idx + 1]
            list[idx + 1] = temp
            _skills.value = list
            saveSettings()
        }
    }

    /**
     * Turn a skill on/off for one persona.
     *
     * A separate entry point from [updatePersona] so the whole app mutates this field through
     * one place: it rebuilds the persona in place (preserving list order, which the persona
     * selector, deletion fallback and reorder all depend on) and saves immediately, rather
     * than leaving a caller to remember.
     */
    fun setSkillEnabledForPersona(personaName: String, skillId: String, enabled: Boolean) {
        val current = _personas.value
        val idx = current.indexOfFirst { it.name == personaName }
        if (idx == -1) return
        val persona = current[idx]
        val ids = persona.enabledSkillIds
        val updated = when {
            enabled && skillId !in ids -> ids + skillId
            !enabled && skillId in ids -> ids - skillId
            else -> return
        }
        // Rebuild rather than mutate in place: _personas.value hands out an immutable List.
        // Replacing only this index keeps every other persona's identity — and the list ORDER
        // the selector, reorder controls and deletion fallback depend on — untouched.
        _personas.value = current.toMutableList().also { it[idx] = persona.copy(enabledSkillIds = updated) }
        saveSettings()
    }

    /**
     * Outcome of a manual "send me a message now" request (the persona editor's button).
     *
     * This reports whether the request was ACCEPTED, not whether a message was produced: the
     * turn itself runs off the main thread afterwards, and may legitimately end in silence or
     * in a logged failure (see `runSpontaneousTurn`). Accepted means every guard passed and
     * the turn was dispatched — the same contract the scheduled path will have.
     */
    sealed interface SpontaneousNowOutcome {
        /** Every guard passed and the turn is running. */
        data object Accepted : SpontaneousNowOutcome

        /** Do not send. Shown verbatim in the editor, so it must read as UI text. */
        data class Refused(val reason: String) : SpontaneousNowOutcome
    }

    /**
     * The "send me a message now" entry point. Re-runs the whole guard chain against LIVE
     * service state at request time — the persona's own `spontaneousEnabled` flag, then
     * [`Persona.spontaneousEligibility`] against the current server lists — because the
     * commit that enabled the flag may predate a deleted server or a changed model.
     *
     * The guards are evaluated synchronously (they are in-memory reads, and the caller is a
     * UI click that needs an immediate answer); the turn itself is dispatched onto
     * [serviceScope], so the network round-trip never blocks the caller.
     */
    fun sendSpontaneousMessageNow(personaName: String): SpontaneousNowOutcome {
        // Fail-closed, by NAME: a rename that already committed misses the lookup and the
        // request refuses, rather than addressing the persona that now sits at the index
        // (see setSkillEnabled's note in PersonaEditor for the same lookup's caveat).
        val persona = personas.value.find { it.name == personaName }
            ?: return SpontaneousNowOutcome.Refused("This persona no longer exists.")
        // Master switch first: a persona never opted in must never send on demand.
        if (!persona.spontaneousEnabled) {
            return SpontaneousNowOutcome.Refused("Turn spontaneous messages on for this persona first.")
        }
        when (val verdict = persona.spontaneousEligibility(ollamaServerNames(), cloudProviderNames())) {
            is RoomEligibility.Eligible -> { /* fall through */ }
            is RoomEligibility.Blocked -> return SpontaneousNowOutcome.Refused(verdict.reason)
        }
        serviceScope.launch {
            try {
                // A turn for THIS persona only. Deliberately not routed through the chat
                // pipeline — see the isolation note at the top of AssistantServiceSpontaneous.kt.
                runSpontaneousTurn(persona)
            } catch (e: CancellationException) {
                // Service teardown / scope cancel: cooperative, nothing to report.
                throw e
            } catch (e: Exception) {
                // Silent by design: a background message that could not be produced must not
                // surface as an error bubble in a conversation the user never asked about.
                Log.e("AssistantService", "Spontaneous turn for '${persona.name}' failed: ${e.message}")
            }
        }
        return SpontaneousNowOutcome.Accepted
    }

    fun removePersona(index: Int) {
        val current = _personas.value.toMutableList()
        if (index in current.indices) {
            val removedPersona = current[index]
            val removedName = removedPersona.name
            current.removeAt(index)
            // Fix #4: deleting the ACTIVE persona used to leave currentPersonaName
            // pointing at a name that no longer existed — currentPersona then returned
            // null, sends ran on the UI's stale cached persona object, and the debounced
            // persistSettings() (plus switchPersona's outgoing-history save) re-persisted
            // the in-memory conversation under the deleted persona's now-orphaned
            // storage key. Re-point the selection BEFORE publishing the shrunken list
            // (same re-pointing precedent as updatePersona's rename handling above), so
            // at every observation point the active name exists in the list.
            if (currentPersonaName == removedName) {
                val fallback = current.firstOrNull()
                if (fallback != null) {
                    // Detach first: switchPersona saves the outgoing persona's in-memory
                    // history under its recorded owner, which while the removed persona is
                    // still selected IS the deleted persona — the save would write it to the
                    // deleted persona's orphaned key. Detached (name null, owner still the
                    // removed persona, L5), that save is skipped: the deleted conversation dies
                    // with its persona, and the fallback loads its OWN saved history.
                    currentPersonaName = null
                    switchPersona(fallback)
                } else {
                    // Removed the last persona: the documented no-selection state.
                    // The UI re-seeds the default personas on its next bind.
                    currentPersonaName = null
                    // L5: nothing is on screen for a persona any more, so the recorded owner
                    // goes with the name (persistSettings would otherwise keep keying the
                    // history saves off the deleted persona's name).
                    messagesOwnerName = null
                    _messages.value = emptyList()
                }
            }
            _personas.value = current
            // Spontaneous scheduling (step 5): drop the chain for the name that no longer
            // exists. Without this the timer would keep ticking until its next run, at
            // which point the guard would end it anyway — this just makes it immediate.
            removeSpontaneousSchedule(removedName)
            // Followup to Fix #4: the removal must also drop the persona's persisted
            // history — a genuine deletion leaves no persona_messages_<name> entry
            // behind on disk. (Rename is handled by migratePersonaHistory in
            // updatePersona and MIGRATES rather than deletes, so this only ever fires
            // on true removal. Removing a name with no saved entry is a no-op.)
            // F1: deletePersonaHistory is the SHARED deletion surface, so the persona's
            // spontaneous delivery state dies in this same lane call — a leftover state
            // entry would keep counting toward the global daily cap, suppressing the
            // surviving personas' messages for the rest of the day.
            // L5: the delete runs in the persona-history IO lane (joining the swap/clear above
            // first), because the swap it just started — and the persistSettings() a few lines
            // below, which still keys off the removed persona's recorded owner — may write that
            // history entry; ordering the delete last guarantees nothing resurrects it
            // afterwards.
            val deletedHistoryName = removedName
            val previousHistoryIo = historyIoJob
            historyIoJob = serviceScope.launch {
                previousHistoryIo?.join()
                withContext(Dispatchers.IO) {
                    settingsManager.deletePersonaHistory(deletedHistoryName)
                }
            }
            // Artwork cleanup rides along with the history cleanup above: the persona's
            // app-owned icon/background JPEGs are unreachable once it is gone, so they
            // would sit in filesDir forever. Driven off the captured Persona object
            // rather than the name, because SettingsManager's stored persona list is
            // only rewritten by saveSettings() below — a name lookup would still find
            // (and therefore leave alone) the persona being removed.
            settingsManager.deletePersonaArtworkFiles(removedPersona)
            // Deleting a persona must also drop it from every room it belonged to —
            // same reasoning as the single-chat history delete above: a surviving
            // membership reference is a name that can never resolve again.
            removeRoomMemberEverywhere(removedName)
            saveSettings()
        }
    }

    fun movePersonaUp(index: Int) {
        val current = _personas.value.toMutableList()
        if (index in 1 until current.size) {
            val temp = current[index]
            current[index] = current[index - 1]
            current[index - 1] = temp
            _personas.value = current
            saveSettings()
        }
    }

    fun movePersonaDown(index: Int) {
        val current = _personas.value.toMutableList()
        if (index != -1 && index < current.size - 1) {
            val temp = current[index]
            current[index] = current[index + 1]
            current[index + 1] = temp
            _personas.value = current
            saveSettings()
        }
    }

    fun addManualModel(model: String) {
        if (!_manualModels.value.contains(model)) {
            _manualModels.value = _manualModels.value + model
            saveSettings()
        }
    }

    fun removeManualModel(model: String) {
        _manualModels.value = _manualModels.value - model
        saveSettings()
    }

    fun resetTotalCost() {
        _totalCost.value = 0.0
        _sessionUsage.value = UsageInfo()
        saveSettings()
    }

    fun isFirstRun(): Boolean = settingsManager.isFirstRun()
    fun setFirstRunComplete() = settingsManager.setFirstRunComplete()

    fun getSearxngUrl(): String? = settingsManager.getSearxngUrl()
    fun saveSearxngUrl(url: String) = settingsManager.saveSearxngUrl(url)

    fun getUserLocation(): String? = settingsManager.getUserLocation()
    fun saveUserLocation(location: String) = settingsManager.saveUserLocation(location)

    fun getRagServerUrl(): String? = settingsManager.getRagServerUrl()
    fun saveRagServerUrl(url: String) = settingsManager.saveRagServerUrl(url)

    fun getRagUsername(): String? = settingsManager.getRagUsername()
    fun saveRagUsername(username: String) = settingsManager.saveRagUsername(username)
    fun getRagPassword(): String? = settingsManager.getRagPassword()
    fun saveRagPassword(password: String) = settingsManager.saveRagPassword(password)

    fun exportBackup(): String = settingsManager.exportBackup()

    // Persona artwork import. Suspends on Dispatchers.IO because it decodes and
    // re-encodes a full-sized photo — far too heavy for the main thread. Returns the
    // app-owned path to persist on the Persona, or null when the pick was unreadable
    // (the caller then keeps the persona's previous artwork).
    suspend fun savePersonaImage(uri: Uri, slot: String): String? =
        withContext(Dispatchers.IO) { settingsManager.savePersonaImage(uri, slot) }

    // Materializes [uri] into a NEW app-owned message image. This is the share target's
    // arrival path, where the URI belongs to whoever sent it (a gallery, a browser) and the
    // only way to keep it is to copy it before the grant dies.
    suspend fun saveMessageImage(uri: Uri): String? =
        withContext(Dispatchers.IO) { settingsManager.saveMessageImage(uri) }

    // What a pending composer attachment is SENT as. An image the share target already
    // materialized on arrival is reused rather than re-encoded, so one shared image costs
    // exactly one file for its whole life; anything else (the picker's URI) is copied in.
    suspend fun messageImagePath(uri: Uri): String? =
        withContext(Dispatchers.IO) { settingsManager.messageImagePath(uri) }

    // The composer's remove button. Fire-and-forget on the service scope: the UI must not
    // wait on IO to drop an attachment, the deletion is idempotent, and this is the one
    // deletion that has to survive the composer going away (it is not the composer's file).
    fun discardMessageImage(uri: Uri?) {
        val target = uri ?: return
        serviceScope.launch {
            withContext(Dispatchers.IO) { settingsManager.discardMessageImage(target) }
        }
    }
    fun importBackup(json: String): Boolean {
        // F2: the persona names that could currently be holding an armed spontaneous chain.
        // Snapshotted BEFORE the import because the restore rewrites the stored list and
        // loadSettings republishes it — after either, the vanished names are unrecoverable,
        // and they are exactly what the post-import reconciliation has to disarm.
        val preImportNames = personas.value.map { it.name }.toSet()
        val success = settingsManager.importBackup(json)
        if (success) {
            loadSettings()
            reconcileSpontaneousSchedulesAfterImport(preImportNames)
            warnOnImportedDuplicateNames()
        }
        return success
    }

    /**
     * Report duplicate server/provider names that arrived with a backup, WITHOUT changing
     * them.
     *
     * The uniqueness guard deliberately sits on the add/edit chokepoints and not in
     * `SettingsManager.importBackup`, because a restore is a verbatim copy of another
     * device's state: rejecting or renaming entries there would silently edit a backup the
     * user did not ask us to edit. So a backup written before this guard (or hand-edited)
     * can still load a duplicate pair.
     *
     * What happens with one is left as-is and made SAFE rather than corrected:
     *  - nothing throws, and no name is silently changed, so the restored state matches
     *    the file the user holds;
     *  - `isDirectOllama` / `routesToCloud` resolve such a name through whichever list is
     *    consulted first, so routing is ambiguous but stable, not intermittent;
     *  - the user is told which names collide, and can resolve it with one edit;
     *  - **a rename of either member of the pair is allowed**: the guard excludes the entry
     *    being edited, so it can be renamed out of the collision without touching the other.
     */
    private fun warnOnImportedDuplicateNames() {
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        allServerAndProviderNames().forEach { name ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty() && !seen.add(key)) duplicates.add(name.trim())
        }
        if (duplicates.isEmpty()) return
        val message = "Imported ${duplicates.size} duplicate server name(s): " +
            duplicates.joinToString(", ") + " — rename one of each to tell them apart"
        Log.w("AssistantService", message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    // ── Backup encryption (Fix #7) ───────────────────────────────────────────────
    // API keys, server credentials and RAG passwords used to leave the app as
    // plaintext JSON. Encrypted backups now wrap that JSON in an envelope:
    //   {"encrypted": true, "salt": b64, "iv": b64, "iterations": N, "ciphertext": b64}
    // Key derivation: PBKDF2WithHmacSHA256 (never the raw password as key), 16-byte
    // random salt unique per export. Cipher: AES-256-GCM with a 12-byte random IV —
    // the same AES-GCM family the app already uses for its encrypted preferences.
    // GCM's built-in authentication means a wrong password fails the tag check
    // (AEADBadTagException) instead of decrypting to garbage, which the import path
    // surfaces as a clear "Incorrect password" error.
    private val backupSecureRandom = SecureRandom()
    private val backupKdfIterations = 210_000

    private fun deriveBackupKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encryptBackupJson(plainJson: String, password: String): String {
        val salt = ByteArray(16).also { backupSecureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { backupSecureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveBackupKey(password, salt, backupKdfIterations), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("encrypted", true)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("iterations", backupKdfIterations)
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    private fun decryptBackupJson(envelopeJson: String, password: String): String {
        val envelope = JSONObject(envelopeJson)
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveBackupKey(password, salt, envelope.optInt("iterations", backupKdfIterations)),
            GCMParameterSpec(128, iv)
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun isEncryptedBackup(json: String): Boolean = try {
        JSONObject(json).optBoolean("encrypted", false)
    } catch (e: Exception) {
        false // not JSON at all — the plain-import path will report the format error
    }

    // Encrypted-import handshake: when importBackupFromFile() hits an encrypted
    // envelope it needs the password, and only the UI can collect one. The pending
    // flag drives the UI's password prompt; the answer arrives via
    // submitBackupPassword()/cancelBackupPasswordPrompt() and resumes the SAME
    // serviceScope coroutine that read the file — so detection, decryption and the
    // import itself all stay in the dialog-closure-safe scope even while the prompt
    // is on screen. The identity guard in the finally block keeps a stale waiter
    // from clobbering a newer prompt's flag/deferred.
    private var backupPasswordDeferred: CompletableDeferred<String?>? = null
    private val _backupPasswordRequested = MutableStateFlow(false)
    val backupPasswordRequested = _backupPasswordRequested.asStateFlow()

    fun submitBackupPassword(password: String?) {
        backupPasswordDeferred?.complete(password)
    }

    fun cancelBackupPasswordPrompt() {
        backupPasswordDeferred?.complete(null)
    }

    // Backup export/import run on serviceScope — not the Settings dialog's UI scope —
    // so closing the dialog mid-operation can no longer cancel them and leave a
    // truncated export file or a half-applied import. Toasts work from the foreground
    // service regardless of what's on screen.
    fun exportBackupToFile(uri: Uri, password: String? = null) {
        serviceScope.launch {
            try {
                val backup = exportBackup()
                val output = if (password.isNullOrBlank()) backup
                else withContext(Dispatchers.IO) { encryptBackupJson(backup, password) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(output.toByteArray())
                    }
                }
                Toast.makeText(
                    applicationContext,
                    if (password.isNullOrBlank()) "Backup exported successfully"
                    else "Encrypted backup exported successfully",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun importBackupFromFile(uri: Uri) {
        serviceScope.launch {
            try {
                // Retire any stale password waiter from an abandoned import — it resumes
                // with null, and its finally's identity guard leaves this import's
                // prompt state alone.
                backupPasswordDeferred?.complete(null)
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (json == null) return@launch

                // Fix #7: auto-detect the envelope. Plain backups (and legacy ones)
                // import exactly as before; encrypted ones pause here for the UI to
                // collect the password, then resume in this same coroutine.
                if (!isEncryptedBackup(json)) {
                    val success = importBackup(json)
                    Toast.makeText(
                        applicationContext,
                        if (success) "Import successful" else "Import failed: Invalid format",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val deferred = CompletableDeferred<String?>()
                backupPasswordDeferred = deferred
                _backupPasswordRequested.value = true
                val password = try {
                    deferred.await()
                } finally {
                    if (backupPasswordDeferred === deferred) {
                        _backupPasswordRequested.value = false
                        backupPasswordDeferred = null
                    }
                }
                if (password.isNullOrBlank()) {
                    Toast.makeText(applicationContext, "Import cancelled", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val plainJson = try {
                    withContext(Dispatchers.IO) { decryptBackupJson(json, password) }
                } catch (e: Exception) {
                    // GCM authentication failure — wrong password (or a corrupted
                    // file). Never garbage data, never a partial import.
                    Toast.makeText(applicationContext, "Import failed: Incorrect password", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val success = importBackup(plainJson)
                Toast.makeText(
                    applicationContext,
                    if (success) "Import successful" else "Import failed: Invalid format",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fix for Android 15/16 (targetSDK 37): RECORD_AUDIO must be granted before
        // starting a foreground service with the microphone type. If not granted yet,
        // start as a regular service first and promote to foreground once permission is granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForeground(NOTIFICATION_ID, createNotification("Ready to help"))
        }
        return START_STICKY
    }

    /**
     * Promotes this service to a foreground service with the microphone type.
     * Call this after RECORD_AUDIO permission is granted.
     */
    fun promoteToForeground() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForeground(NOTIFICATION_ID, createNotification("Ready to help"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // C1: final synchronous flush — if the service is torn down inside the debounce
        // window, the pending state must reach disk before anything is cancelled.
        // Serialized against an in-flight debounced write via the same mutex.
        saveSettingsJob?.cancel()
        runBlocking { saveMutex.withLock { persistSettings() } }
        serviceScope.cancel()
        stopAudio()
        tts.stop()
        tts.shutdown()
        // Safety: release the proximity listener with the service (normally
        // already unregistered by the state watcher's SPEAKING-exit path).
        unregisterProximityBargeIn()
        vadRecorder?.stop()
        vadDetector?.close()
        // vadDetector is a file-level static, so it survives this destroy and would be
        // seen non-null by a recreated service's startVadListening() — silently reusing
        // this closed detector (isSpeech() would throw on the closed session on every
        // chunk, get caught, and return 0f forever: hands-free dead without any error
        // surface until the process dies). Null after close so a same-process restart
        // builds a fresh detector and session. (OrtEnvironment.getEnvironment() is a
        // process-wide singleton whose close() is a no-op in ORT 1.11+, so recreating
        // the detector is safe and cheap — only the session is genuinely per-instance.)
        vadDetector = null
        // Released under espeakLock so teardown can never race an in-flight warm-up
        // construction (release-then-assign would strand a live native engine).
        // Backing field, NOT the espeakEngine getter — the getter would construct an
        // engine here just to release it, i.e. main-thread init at teardown for every
        // user, which would also defeat warmUpEspeakEngine()'s zero-cost guarantee for
        // setups without a BUNDLED_ESPEAK persona. Nulling afterwards means any
        // straggler accessor lazily builds a fresh engine instead of getting the
        // terminated one. Deliberately placed AFTER serviceScope.cancel()/stopAudio()
        // so any in-flight kotlinx blocking synthesize coroutine has been cancelled —
        // see the documented limitation below.
        synchronized(espeakLock) {
            _espeakEngine?.release()
            _espeakEngine = null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Assistant Service Channel", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    internal fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    fun startRecording() {
        if (!requestAssistantFocus()) {
            _state.value = AssistantState.IDLE
            updateNotification("Ready to help")
            return
        }
        _voiceDuration.value = 0
        val file = File(cacheDir, "recording_${System.currentTimeMillis()}.m4a")
        val mr = MediaRecorder(this)
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setOutputFile(file.absolutePath)
        mr.prepare()
        mr.start()
        recorder = mr
        outputFile = file
        _state.value = AssistantState.LISTENING
        updateNotification("Listening...")

        // Poll amplitude for the Celestial HUD visualizer while manual recording is active.
        serviceScope.launch {
            while (recorder == mr && _state.value == AssistantState.LISTENING) {
                try {
                    val max = mr.maxAmplitude
                    val amp = (max / 32767f).coerceIn(0f, 1f)
                    if (amp > 0.001f) Log.d("MicAmplitude", "Manual: $amp")
                    _micAmplitude.value = amp
                } catch (e: Exception) {
                    break
                }
                delay(100)
            }
            _micAmplitude.value = 0f
        }
    }

    fun stopRecording(currentPersona: Persona) {
        // Fix: the recorder cleanup is shared with stopEverything()'s discard path
        // via stopActiveRecording() — the identical stop/release sequence.
        stopActiveRecording()
        // Focus release is CONDITIONAL on whether playback will genuinely follow
        // this turn. The unconditional abandon created a 2-5s un-duck window
        // during THINKING: other apps' audio (e.g. music) jumped back to full
        // volume the moment speech ended, then re-ducked when the response
        // started playing — a jarring jump-then-drop.
        //
        // Retain through THINKING only when the turn will actually speak:
        //   - a model is configured (sendAudioToServer's guard otherwise lands
        //     the turn in IDLE with an error bubble, no playback), and
        //   - voiceMode != NONE (playResponse's NONE branch bails to IDLE), and
        //   - TTS is not silenced (with silence on, every playback entry point
        //     early-returns to IDLE WITHOUT requesting focus — a retained
        //     request would be held indefinitely, the original bug).
        // When playback does follow, its requestAssistantFocus() re-requests the
        // SAME held AudioFocusRequest (same usage). A focus re-request by the
        // current holder dispatches no focus change to other apps, so ducking
        // stays continuous from speech-end through playback.
        //
        // The IMMEDIATE abandon is kept exactly as before on the no-playback
        // paths (no model / VoiceMode.NONE / silenced) — that unconditional
        // abandon was added to fix the earlier bug where the transient request
        // was held indefinitely on error or NONE paths, ducking other apps
        // until the app was killed. Turns retained through THINKING that then
        // fail (server error, empty transcription, user stop) abandon at their
        // terminal point — see the no-play abandon sites in
        // sendAudioToServer/performCloudChat and the playback entry bail-outs
        // (stopEverything() and the playback teardown paths already abandon).
        val playbackWillFollow = currentPersona.model.isNotBlank() &&
            currentPersona.voiceMode != VoiceMode.NONE &&
            !silenced.value
        if (!playbackWillFollow) {
            abandonAssistantFocus()
        }
        // Fix #10: claim and clear the field BEFORE sending. stopRecording() can run
        // twice (double-tap on the stop control, or a state race re-invoking it) —
        // without this, every call after the first re-sent the same previously
        // recorded file as if it were a new user turn. A repeat call now finds a
        // null field and has nothing left to send.
        val fileToSend = outputFile
        outputFile = null
        fileToSend?.let { sendAudioToServer(it, currentPersona) }
    }

    // Fix: stops and releases an active manual recording (the red STOP button is
    // reachable during LISTENING). The partial output file is left for the caller:
    // stopRecording() sends it, stopEverything() discards it.
    fun stopActiveRecording() {
        Log.d("MicAmplitude", "Reset to 0 (stopActiveRecording)")
        _micAmplitude.value = 0f
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // A too-short recording makes stop() throw — the recorder still needs
            // its release below.
            android.util.Log.w("AssistantService", "Recorder stop failed (likely a too-short recording)", e)
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
            android.util.Log.e("AssistantService", "Recorder release failed", e)
        }
        recorder = null
    }

    fun testAudio(text: String, mode: VoiceMode, backendUrl: String, targetLanguage: String, isTranslator: Boolean, engine: String = "kokoro", kokoroVoice: String = "af_heart") {
        when (mode) {
            VoiceMode.NONE -> {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
            VoiceMode.SYSTEM_TTS -> speakTextOnDevice(text)
            VoiceMode.BUNDLED_ESPEAK -> {
                val dummy = Persona("test", Color.Gray, "", "", isTranslator = isTranslator, targetLanguage = targetLanguage)
                speakWithEspeak(text, dummy)
            }
            VoiceMode.GATEWAY -> {
                testGatewayVoice(text, backendUrl, targetLanguage, engine, kokoroVoice)
            }
        }
    }

    fun switchPersona(persona: Persona) {
        // Persona switch must not leave the OUTGOING persona's turn running underneath
        // the incoming persona's screen. Two things went wrong before this:
        //  - an in-flight chunked Gateway TTS sequence kept AUDIO playing (the sequence
        //    only ever halts on a ttsGeneration bump, and nothing bumped it here), and
        //  - its polling coroutine kept republishing _ttsPlaybackFraction every ~100ms
        //    against the NEW persona's message list — the wrong list entirely, so the
        //    UI could pin/highlight a bubble from a response it no longer displays.
        // stopEverything() (not just stopAudio()) matches the existing Stop-button
        // semantics: it stops playback AND bumps the chat generation, so an in-flight
        // chat/stream/synthesis turn can no longer append its reply into the outgoing
        // persona's history after the user moved on (isChatRequestCurrent/
        // isChatContextCurrent discard it at each apply-gate).
        // Deliberately ordered BEFORE the outgoing-history save below: stopEverything()
        // does not touch _messages, so that save still persists exactly the conversation
        // the user was looking at when they switched. (L5: that save is asynchronous now —
        // the IO runs on Dispatchers.IO — so the outgoing snapshot is still taken
        // synchronously HERE, at the same point in the sequence; only the IO moved.)
        // Idle switches (the common case)
        // are cheap — every teardown step self-guards: currentPlayer/currentAudioTrack/
        // recorder/outputFile/audioFocusRequest are all null and _state is already IDLE,
        // so the state+notification reset is skipped entirely (tts.stop() is still called
        // but is a no-op with nothing queued, and nextChatRequestSeq() is a bare ++).
        // The Stage-2 StateFlow writes are equality-conflated, so clearing already-null
        // values emits nothing to collectors. What an idle switch does leave behind is
        // only the two counter bumps (ttsGeneration, chatRequestSeq) — the same bumps the
        // Stop button makes, and harmless with no in-flight work for them to invalidate.
        stopEverything()
        // L5: capture the outgoing conversation and the incoming load in ONE asynchronous
        // operation, off Main:
        //  - the snapshot is taken synchronously here, before `currentPersonaName` moves, so it
        //    is still exactly the conversation the user was looking at;
        //  - outgoingOwner is the persona those visible messages BELONG to. It matches
        //    outgoingName in every settled state; when it does not (a previous swap's load never
        //    published, so what is on screen is somebody else's conversation), the outgoing
        //    write is skipped — that persona's stored history is still the authoritative one
        //    and overwriting it with a foreign list would corrupt it;
        //  - the write is chained BEFORE the incoming read inside the same job, and each job
        //    joins the previous one, so the original save-then-load ordering is preserved;
        //  - the load only publishes when it is still the newest swap (swapSeq), so a fast
        //    A->B->A tap sequence cannot end up showing the older persona's messages.
        val outgoingName = currentPersonaName
        val outgoingOwner = messagesOwnerName ?: outgoingName
        val outgoingHistory = _messages.value
        val outgoingLoaded = outgoingOwner != null && outgoingOwner == outgoingName
        // Non-null write target: the job below must not need a safe call (which would read
        // as "the save is optional") to prove what outgoingLoaded already proved.
        val outgoingSaveTarget = outgoingOwner?.takeIf { outgoingLoaded }
        val swapSeq = ++personaHistoryLoadSeq
        val previousHistoryIo = historyIoJob
        currentPersonaName = persona.name
        historyIoJob = serviceScope.launch {
            previousHistoryIo?.join()
            if (outgoingSaveTarget != null) {
                withContext(Dispatchers.IO) {
                    settingsManager.savePersonaMessages(outgoingSaveTarget, outgoingHistory)
                }
            }
            val restored = withContext(Dispatchers.IO) {
                settingsManager.getPersonaMessages(persona.name)
            }
            if (swapSeq == personaHistoryLoadSeq) {
                _messages.value = restored ?: emptyList()
                messagesOwnerName = persona.name
            }
        }
        // Just activated a persona mid-session — if it voices through bundled eSpeak,
        // start building that engine now on Dispatchers.IO instead of paying lazy init
        // on Main at the first playback. Idempotent (no-op if already warmed/built).
        if (persona.voiceMode == VoiceMode.BUNDLED_ESPEAK) warmUpEspeakEngine()
    }

    // The persona the service currently considers active, DERIVED through the same
    // [resolveCurrentPersona] join that `currentPersonaFlow` uses, so the synchronous read and
    // the observable one can never disagree. Kept because service-internal logic needs a pull
    // read with no dispatch delay; observers should collect `currentPersonaFlow`. Null when no
    // persona is selected yet, or the selected name no longer exists in the list.
    val currentPersona: Persona?
        get() = resolveCurrentPersona(currentPersonaName, _personas.value)

    fun clearMessages() { 
        // Clear-chat is the same "move on" gesture as a persona switch: an in-flight
        // Gateway TTS sequence must not keep speaking underneath a conversation the user
        // just emptied, and its poll coroutine must not keep republishing playback state
        // against the now-empty list (stale highlight / wrong reveal), nor may the
        // discarded turn append a reply into the cleared history. stopEverything() gives
        // this the Stop-button semantics: audio halted + generation bumped so every
        // in-flight apply-gate discards. Ordered before the empty-list write below, which
        // is the only thing that touches _messages here.
        stopEverything()
        
        // File cleanup: delete all audio/image files in the transcript before clearing it.
        _messages.value.forEach { msg ->
            msg.audioFilePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
            msg.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
            msg.generatedImagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
        }

        // L5: the empty-list write is what must be observed synchronously (the tests and the
        // UI both read it right after), but the persisted history is IO — so publish the empty
        // list here and clear the owner (nothing on screen belongs to a persona any more),
        // then write the empty history to disk off Main. saveSettings() below then saves the
        // live name + the empty list it just published, i.e. the same pair as before.
        personaHistoryLoadSeq++
        _messages.value = emptyList()
        messagesOwnerName = null
        val clearedName = currentPersonaName
        val previousHistoryIo = historyIoJob
        historyIoJob = serviceScope.launch {
            previousHistoryIo?.join()
            withContext(Dispatchers.IO) {
                clearedName?.let { settingsManager.savePersonaMessages(it, emptyList()) }
            }
        }
        saveSettings() 
    }
    fun updateMessage(index: Int, newText: String) {
        val current = _messages.value.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(text = newText)
            _messages.value = current
            saveSettings()
        }
    }

    fun deleteMessage(index: Int) {
        val current = _messages.value.toMutableList()
        if (index in current.indices) {
            val removed = current.removeAt(index)
            _messages.value = current
            // File cleanup: drop the message's cached audio/image now that it's
            // gone from the transcript, so they don't leak in filesDir.
            removed.audioFilePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) { }
            }
            removed.imagePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) { }
            }
            removed.generatedImagePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) { }
            }
            saveSettings()
        }
    }


    fun fetchCloudModels(api: CloudApiSetting) {
        if (api.apiKey.isBlank() && api.icon != "C" && api.icon != "CL") {
            return
        }
        incrementModelFetchCount()
        val baseUrl = api.baseUrl.trim().removeSuffix("/")
        serviceScope.launch(Dispatchers.IO) {
            // #3 (cloud) fix: do NOT snapshot _serverStatus at coroutine start and write
            // it back wholesale at the end — multiple cloud providers fetch concurrently
            // (one coroutine per provider from loadSettings), so the last provider to
            // finish would clobber every sibling's status entry with its own stale
            // snapshot. Same stale-snapshot bug the B3 fix removed from fetchModels.
            // Track JUST this provider's own outcome, and at write-time update only that
            // one entry in the LIVE map (read-modify-write), leaving concurrent updates
            // from other providers untouched.
            var status: String? = null
            try {
                // Cline Bot has no /models endpoint — use the static model list directly.
                if (api.icon == "CL") {
                    val models = CLINE_BOT_MODELS.map { "[${api.name}] $it" }
                    withContext(Dispatchers.Main) {
                        val current = _fetchedCloudModels.value.toMutableMap()
                        current[api.name] = models
                        _fetchedCloudModels.value = current
                        // Clear any previous error status on success
                        val statusMap = _serverStatus.value.toMutableMap()
                        statusMap.remove(api.name)
                        _serverStatus.value = statusMap
                    }
                    // M1: no explicit decrement here — the finally below is the single
                    // decrement point for every exit of this coroutine (this early
                    // return included). The old explicit decrement on this path
                    // double-decremented (one increment at the top, two decrements):
                    // under concurrent provider fetches the counter could hit zero
                    // while another fetch was still in flight, prematurely clearing
                    // _isLoadingModels — the exact stuck-spinner bug B3/M1 prevent.
                    return@launch
                }
                val models = when (api.icon) {
                    "G" -> { // Google
                        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${api.apiKey}"
                        standardClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) {
                                status = "Google API Error: ${response.code}"
                                return@use emptyList<String>()
                            }
                            val json = JSONObject(response.body.string())
                            val array = json.getJSONArray("models")
                            val rawModels = mutableListOf<JSONObject>()
                            for (i in 0 until array.length()) {
                                rawModels.add(array.getJSONObject(i))
                            }

                            rawModels.filter { j ->
                                val methods = j.optJSONArray("supportedGenerationMethods")?.let { arr ->
                                    List(arr.length()) { i -> arr.getString(i) }
                                } ?: emptyList()
                                methods.contains("generateContent") && j.getString("name").contains("gemini", ignoreCase = true)
                            }
                            .sortedByDescending { j ->
                                val name = j.getString("name").substringAfter("models/")
                                val versionMatch = Regex("""\d+\.\d+""").find(name)
                                versionMatch?.value?.toDoubleOrNull() ?: 0.0
                            }
                            .let { selectDiverseModelSubset(it, MAX_CLOUD_MODELS_SHOWN) { j -> j.getString("name").substringAfter("models/") } }
                            .map { "[${api.name}] ${it.getString("name").substringAfter("models/")}" }
                        }
                    }
                    "A" -> { // Anthropic
                        val url = "$baseUrl/v1/models"
                        val request = Request.Builder()
                            .url(url)
                            .header("x-api-key", api.apiKey)
                            .header("anthropic-version", "2023-06-01")
                            .build()
                        standardClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                status = "Anthropic Error: ${response.code}"
                                return@use emptyList<String>()
                            }
                            val json = JSONObject(response.body.string())
                            val array = json.getJSONArray("data")
                            val rawModels = mutableListOf<JSONObject>()
                            for (i in 0 until array.length()) {
                                rawModels.add(array.getJSONObject(i))
                            }

                            rawModels.sortedByDescending { it.optString("created_at", "") }
                                .let { selectDiverseModelSubset(it, MAX_CLOUD_MODELS_SHOWN) { m -> m.optString("id") } }
                                .map { "[${api.name}] ${it.getString("id")}" }
                        }
                    }
                    else -> { // OpenAI, DeepSeek, Custom
                        val url = if (baseUrl.endsWith("/models")) baseUrl else "$baseUrl/models"
                        standardClient.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${api.apiKey}").build()).execute().use { response ->
                            if (!response.isSuccessful) {
                                status = when(response.code) {
                                    401 -> "Invalid API Key"
                                    429 -> "Rate limit exceeded"
                                    else -> "Fetch failed: ${response.code}"
                                }
                                return@use emptyList<String>()
                            }
                            val json = JSONObject(response.body.string())
                            val array = json.getJSONArray("data")
                            val rawModels = mutableListOf<JSONObject>()
                            for (i in 0 until array.length()) {
                                rawModels.add(array.getJSONObject(i))
                            }

                            val filtered = if (api.icon == "C" || api.icon == "D") {
                                rawModels.map { "[${api.name}] ${it.getString("id")}" }
                            } else {
                                // OpenAI specific filtering
                                rawModels.filter { j ->
                                    val id = j.getString("id").lowercase()
                                    val isChat = id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
                                    val isNonChat = id.contains("whisper") || id.contains("tts") || id.contains("embedding") || id.contains("dall-e") || id.contains("moderation")
                                    isChat && !isNonChat
                                }
                                .sortedByDescending { it.optLong("created", 0) }
                                .let { selectDiverseModelSubset(it, MAX_CLOUD_MODELS_SHOWN) { m -> m.optString("id") } }
                                .map { "[${api.name}] ${it.getString("id")}" }
                            }
                            filtered
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val current = _fetchedCloudModels.value.toMutableMap()
                    current[api.name] = models
                    _fetchedCloudModels.value = current
                    // #3: apply ONLY this provider's entry into the LIVE status map —
                    // success clears it (as the old start-of-fetch snapshot.remove did),
                    // an error records it. Never a wholesale write: a sibling provider
                    // finishing concurrently keeps its own entry untouched.
                    val statusMap = _serverStatus.value.toMutableMap()
                    val ownStatus = status
                    if (ownStatus != null) statusMap[api.name] = ownStatus else statusMap.remove(api.name)
                    _serverStatus.value = statusMap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // #3: read-modify-write the LIVE map here too — only our own entry.
                withContext(Dispatchers.Main) {
                    val statusMap = _serverStatus.value.toMutableMap()
                    statusMap[api.name] = e.message ?: "Cloud fetch failed"
                    _serverStatus.value = statusMap
                }
            } finally {
                // M1: the counter is Main-confined (see the block comment above) — this
                // finally runs on Dispatchers.IO, so hop back to Main before
                // decrementing. Matches how fetchModels decrements.
                withContext(Dispatchers.Main) { decrementModelFetchCount() }
            }
        }
    }


    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val pinnedTrustManager = object : javax.net.ssl.X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
                val host = (socket as? javax.net.ssl.SSLSocket)?.handshakeSession?.peerHost ?: "unknown"
                verify(chain, host)
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
                val host = engine?.peerHost ?: "unknown"
                verify(chain, host)
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                verify(chain, "unknown")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            private fun verify(chain: Array<out X509Certificate>?, host: String) {
                if (chain == null || chain.isEmpty()) return
                val cert = chain[0]
                val fingerprint = sha256Fingerprint(cert)
                val trusted = settingsManager.getTrustedCertificates()
                val stored = trusted[host]

                if (stored == null || stored != fingerprint) {
                    _pendingCertApproval.value = CertApprovalRequest(host, fingerprint, cert)
                    throw javax.net.ssl.SSLHandshakeException(if (stored == null) "New self-signed cert" else "Cert changed!")
                }
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(pinnedTrustManager), SecureRandom())
        
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, pinnedTrustManager)
            // S1: validate the presented cert's SANs against the actual host (RFC 2818/6125,
            // incl. IP-address hosts) instead of accepting any hostname. The prior blanket
            // acceptance let an attacker present a cert not even claiming the target host on
            // the crucial first-connection trust decision; the pinning below still protects
            // against post-approval impersonation.
            .hostnameVerifier(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }


    companion object {
        // Proximity barge-in debounce window (see the comment block on the
        // proximity fields above). 600ms: a deliberate wave-over holds near
        // well past this; incidental hand-over-sensor while interacting with
        // the screen (scrolling the transcript, adjusting grip) usually
        // doesn't. Discovered via the run-4 HUD session: a 200ms window fired
        // interrupts within seconds of playback starting when the user's hand
        // rested near the top-edge sensor while using the new UI.
        private const val PROXIMITY_DEBOUNCE_MS = 600L

        // Grace period: the listener arms this long AFTER SPEAKING begins, so
        // the hand that just started the response (tapped send/planet/mic and
        // is still near the top edge) can't trigger an instant interrupt.
        private const val PROXIMITY_ARM_DELAY_MS = 1200L

        private const val CHANNEL_ID = "assistant_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val SAVE_DEBOUNCE_MS = 400L
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVadListening() {
        if (_serverBases.value.isEmpty()) {
            updateNotification("No server configured")
            return
        }

        if (vadDetector == null) {
            vadDetector = VADDetector(this)
        }

        if (vadRecorder == null) {
            vadRecorder = VADAudioRecorder(
                detector = vadDetector!!,
                cacheDir = cacheDir,
                scope = serviceScope,
                onSpeechStart = {
                    _state.value = AssistantState.LISTENING
                    updateNotification("Listening (VAD)...")
                },
                onSpeechEnd = { file ->
                    // Resolve the persona fresh at speech-end time: a hands-free session
                    // outlives persona switches, and the old start-of-session capture ran
                    // every utterance with (and then had it silently discarded by
                    // isChatContextCurrent against) whatever persona was first in the list.
                    // Shares the one join with currentPersona/currentPersonaFlow; the extra
                    // fallback is this path's own requirement — it must send SOMETHING.
                    val resolvedPersona = resolveCurrentPersona(currentPersonaName, personas.value)
                        ?: personas.value.firstOrNull() ?: DEFAULT_PERSONAS[0]
                    sendAudioToServer(file, resolvedPersona)
                },
                onAmplitudeUpdate = { amp ->
                    if (amp > 0.001f) Log.d("MicAmplitude", "VAD: $amp")
                    if (_state.value == AssistantState.LISTENING) {
                        _micAmplitude.value = amp
                    } else {
                        _micAmplitude.value = 0f
                    }
                }
            )
        }
        vadRecorder?.muted = _micMuted.value
        vadRecorder?.start()
        _handsFreeMode.value = true
    }

    fun stopVadListening() {
        Log.d("MicAmplitude", "Reset to 0 (stopVadListening)")
        vadRecorder?.stop()
        vadRecorder = null
        _handsFreeMode.value = false
        _micAmplitude.value = 0f
        // M2 (ownership): this used to be an unconditional `_state.value = IDLE`.
        // Toggling hands-free off while a VAD-initiated turn was in flight therefore
        // force-reset THINKING even though that turn was still running, and its own
        // finally guard (`if (_state.value == THINKING && isChatRequestCurrent(gen))`)
        // then failed — skipping the abandonment of the audio focus the turn had
        // retained through THINKING (stopRecording keeps it; only an in-flight turn's
        // terminal path releases it). Other apps stayed ducked indefinitely, with the
        // UI already back on IDLE so nothing could ever release it.
        //
        // Deliberate behaviour: stopping hands-free does NOT discard a turn the user
        // already spoke. The turn's own gates publish its reply and release its focus;
        // the toggle only stops listening. (Bumping the generation would be the
        // rejected alternative: it supersedes the turn, so the apply-gates silently
        // throw the user's question away and the focus would have to be abandoned here
        // instead — an accidental answer to "which turn owns the focus", not a
        // deliberate one.)
        //
        // So the reset is gated on OWNERSHIP of the state the in-flight turn itself set:
        //  - THINKING is set exclusively by a chat turn that also recorded itself as the
        //    state's owner (`recordThinkingOwner(generation)`, called at all four THINKING
        //    entries: the voice/text/cloud flows in AssistantServiceChat.kt and Test Voice
        //    in AssistantServiceGateway.kt) and that retained the audio focus through it.
        //    The guard consults that OWNER's generation:
        //      * owner recorded and still current -> a live turn owns THINKING (and the
        //        focus it retained), so the toggle leaves both untouched;
        //      * owner recorded but since superseded/Stopped -> that turn's own finally
        //        deliberately skipped its reset, so the dead THINKING is normalized to IDLE;
        //      * no owner recorded -> this THINKING is not a chat turn's (the Gateway
        //        synthesis feedback path in AssistantServiceAudio.kt), so it is normalized
        //        to IDLE exactly as the unconditional write always did.
        //    Consulting the RECORDED owner (not a fresh snapshot of the sequence) is what
        //    makes this a genuine in-flight test: `currentChatRequestSeq()` is current by
        //    definition, so comparing against it would merely restate
        //    `state == THINKING`. (The sequence cannot advance inside this Main-confined
        //    method, so there is no interleaving to guard against here — only ownership.)
        //  - SPEAKING is playback: forcing the UI to IDLE mid-playback desynced it from the
        //    audio still coming out of the speaker (only playback's own teardown stops it
        //    and writes IDLE), so the toggle leaves it be as well.
        // Every other state (IDLE, and LISTENING — the armed-but-no-turn case) still
        // lands on IDLE with a fresh notification, exactly as the unconditional write
        // always did.
        val stateBeforeToggle = _state.value
        val thinkingOwner = thinkingOwnerGeneration
        val inFlightTurnOwnsState = stateBeforeToggle == AssistantState.THINKING &&
            thinkingOwner != null && isChatRequestCurrent(thinkingOwner)
        if (inFlightTurnOwnsState || stateBeforeToggle == AssistantState.SPEAKING) {
            android.util.Log.d(
                "AssistantService",
                "Hands-free stopped while a turn owns $stateBeforeToggle (owner generation $thinkingOwner) — " +
                    "leaving its state/focus lifecycle to the turn"
            )
        } else {
            if (stateBeforeToggle == AssistantState.THINKING) {
                // Normalizing a THINKING no live turn owns — drop the dead owner so
                // nothing can mistake it for a live turn later.
                thinkingOwnerGeneration = null
            }
            _state.value = AssistantState.IDLE
            updateNotification("Ready to help")
        }
    }
}

private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** Latest GitHub release advertised by the update check (AssistantService.checkForAppUpdate). */
data class UpdateInfo(val version: String, val url: String)
