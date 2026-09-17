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
import androidx.annotation.RequiresPermission
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Index of the in-flight Direct-Ollama streaming placeholder inside
    // _messages (appended on the stream's first chunk, replaced by the final
    // message at apply, removed if it ended up blank). Nullable — null when no
    // stream is in flight or the placeholder was already resolved.
    internal var streamedPlaceholderIndex: Int? = null

    internal fun removeBlankPlaceholderSvc() {
        val idx = streamedPlaceholderIndex ?: return
        val current = _messages.value.toMutableList()
        if (idx in current.indices && current[idx].text.isBlank()) {
            current.removeAt(idx)
            _messages.value = current
        }
    }

    // RAG knowledge-base upload progress/outcome. Hoisted to the service (instead of
    // dialog-local remember state) so it survives Settings-dialog closure: the uploads
    // run on serviceScope and the dialog just collects this flow for display.
    val _knowledgeUploadStatus = MutableStateFlow("")
    val knowledgeUploadStatus = _knowledgeUploadStatus.asStateFlow()

    internal var currentPersonaName: String? = null

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

    internal val _serverBases = MutableStateFlow<List<ServerConfig>>(emptyList())
    val serverBases = _serverBases.asStateFlow()

    internal val _cloudApis = MutableStateFlow<List<CloudApiSetting>>(emptyList())
    val cloudApis = _cloudApis.asStateFlow()

    internal val _customCloudApis = MutableStateFlow<List<CloudApiSetting>>(emptyList())
    val customCloudApis = _customCloudApis.asStateFlow()

    internal val _ollamaBaseUrls = MutableStateFlow<List<ServerConfig>>(emptyList())
    val ollamaBaseUrls = _ollamaBaseUrls.asStateFlow()

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

    private val _favoriteModels = MutableStateFlow<List<String>>(emptyList())
    val favoriteModels = _favoriteModels.asStateFlow()

    internal val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels = _isLoadingModels.asStateFlow()

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
                    AssistantState.IDLE -> vadRecorder?.resume()
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

    fun forceCheckHealth(target: ServerConfig, isGateway: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            checkServerHealth(target, isGateway)
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
        return if (isGateway) {
            target.url.trimEnd('/') + "/"
        } else {
            var base = target.url.trim().removeSuffix("/")
            if (base.endsWith("/v1")) base = base.removeSuffix("/v1")
            if (base.endsWith("/api")) base = base.removeSuffix("/api")
            "$base/api/tags"
        }
    }

    private suspend fun checkServerHealth(target: ServerConfig, isGateway: Boolean) {
        val authorizationHeader = buildAuthorizationHeader(target)
        val url = buildHealthCheckUrl(target, isGateway)

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
            val requestBuilder = Request.Builder().url(url)
            authorizationHeader?.let { requestBuilder.header("Authorization", it) }

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
    internal suspend fun testServerConnection(target: ServerConfig, isGateway: Boolean): ServerConnectionResult {
        val probeClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url(buildHealthCheckUrl(target, isGateway))
                buildAuthorizationHeader(target)?.let { requestBuilder.header("Authorization", it) }
                probeClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        ServerConnectionResult(success = true)
                    } else {
                        ServerConnectionResult(success = false, httpCode = response.code, detail = response.message.ifBlank { null })
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
        
        // If we have personas, initialize the first one as current if not already set
        if (currentPersonaName == null && loadedPersonas.isNotEmpty()) {
            switchPersona(loadedPersonas[0])
        }

        _ollamaBaseUrls.value = settingsManager.getOllamaBases() ?: emptyList()
        _totalCost.value = settingsManager.getTotalCost()
        _favoriteModels.value = settingsManager.getFavoriteModels() ?: emptyList()
        _lastPriceSyncTimestamp.value = settingsManager.getLastPriceSyncTimestamp()
        _celestialUi.value = settingsManager.getCelestialUi()
        _adaptiveTheme.value = settingsManager.getAdaptiveTheme()

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
        settingsManager.savePersonas(_personas.value)
        currentPersonaName?.let { name ->
            settingsManager.savePersonaMessages(name, _messages.value)
        }
        settingsManager.saveOllamaBases(_ollamaBaseUrls.value)
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

    fun addOllamaBase(name: String, url: String, username: String? = null, password: String? = null, authType: AuthType = AuthType.NONE, apiKey: String? = null) {
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
            current[idx] = ServerConfig(newName, newUrl, newUsername, newPassword, newAuthType, newApiKey)
            _ollamaBaseUrls.value = current
            saveSettings()
            fetchModels(current[idx])
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
        _personas.value = _personas.value + persona
        saveSettings()
    }

    fun updatePersona(index: Int, persona: Persona) {
        val current = _personas.value.toMutableList()
        if (index in current.indices) {
            val previous = current[index]
            val oldName = previous.name
            if (persona.name != oldName) {
                // Rename: move the persona's history to the new name-keyed storage
                // entry (it would otherwise be orphaned on disk forever), and when
                // the renamed persona is the active one, keep the selection pointing
                // at it — same in-memory conversation, just a new identity, so no
                // reload and no visible switch.
                settingsManager.migratePersonaHistory(oldName, persona.name)
                if (currentPersonaName == oldName) {
                    currentPersonaName = persona.name
                }
            }
            current[index] = persona
            _personas.value = current
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
                    // history under currentPersonaName — with the removed persona still
                    // selected that would write it to the deleted persona's orphaned key.
                    // Detached, that save is skipped: the deleted conversation dies with
                    // its persona, and the fallback loads its OWN saved history.
                    currentPersonaName = null
                    switchPersona(fallback)
                } else {
                    // Removed the last persona: the documented no-selection state.
                    // The UI re-seeds the default personas on its next bind.
                    currentPersonaName = null
                    _messages.value = emptyList()
                }
            }
            _personas.value = current
            // Followup to Fix #4: the removal must also drop the persona's persisted
            // history — a genuine deletion leaves no persona_messages_<name> entry
            // behind on disk. (Rename is handled by migratePersonaHistory in
            // updatePersona and MIGRATES rather than deletes, so this only ever fires
            // on true removal. Removing a name with no saved entry is a no-op.)
            settingsManager.deletePersonaHistory(removedName)
            // Artwork cleanup rides along with the history cleanup above: the persona's
            // app-owned icon/background JPEGs are unreachable once it is gone, so they
            // would sit in filesDir forever. Driven off the captured Persona object
            // rather than the name, because SettingsManager's stored persona list is
            // only rewritten by saveSettings() below — a name lookup would still find
            // (and therefore leave alone) the persona being removed.
            settingsManager.deletePersonaArtworkFiles(removedPersona)
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
    fun importBackup(json: String): Boolean {
        val success = settingsManager.importBackup(json)
        if (success) loadSettings()
        return success
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
        // the user was looking at when they switched. Idle switches (the common case)
        // are cheap — every teardown step self-guards: currentPlayer/currentAudioTrack/
        // recorder/outputFile/audioFocusRequest are all null and _state is already IDLE,
        // so the state+notification reset is skipped entirely (tts.stop() is still called
        // but is a no-op with nothing queued, and nextChatRequestSeq() is a bare ++).
        // The Stage-2 StateFlow writes are equality-conflated, so clearing already-null
        // values emits nothing to collectors. What an idle switch does leave behind is
        // only the two counter bumps (ttsGeneration, chatRequestSeq) — the same bumps the
        // Stop button makes, and harmless with no in-flight work for them to invalidate.
        stopEverything()
        // Save current persona history before switching
        currentPersonaName?.let { oldName ->
            settingsManager.savePersonaMessages(oldName, _messages.value)
        }
        
        currentPersonaName = persona.name
        _messages.value = settingsManager.getPersonaMessages(persona.name) ?: emptyList()
        // Just activated a persona mid-session — if it voices through bundled eSpeak,
        // start building that engine now on Dispatchers.IO instead of paying lazy init
        // on Main at the first playback. Idempotent (no-op if already warmed/built).
        if (persona.voiceMode == VoiceMode.BUNDLED_ESPEAK) warmUpEspeakEngine()
    }

    // The persona the service currently considers active. The UI seeds itself from
    // this on (re)bind — the service outlives the Activity, so after rotation this
    // IS the user's actual selection, and reading it prevents the old behavior of
    // silently resetting to the default persona on every recreation. Null when no
    // persona is selected yet, or the selected name no longer exists in the list.
    val currentPersona: Persona?
        get() = currentPersonaName?.let { name -> _personas.value.find { it.name == name } }

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
        _messages.value = emptyList()
        currentPersonaName?.let { settingsManager.savePersonaMessages(it, emptyList()) }
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
            current.removeAt(index)
            _messages.value = current
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
                    val resolvedPersona = currentPersonaName?.let { name -> personas.value.find { it.name == name } }
                        ?: personas.value.firstOrNull() ?: DEFAULT_PERSONAS[0]
                    sendAudioToServer(file, resolvedPersona)
                }
            )
        }
        vadRecorder?.muted = _micMuted.value
        vadRecorder?.start()
        _handsFreeMode.value = true
    }

    fun stopVadListening() {
        vadRecorder?.stop()
        vadRecorder = null
        _handsFreeMode.value = false
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
    }
}

private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** Latest GitHub release advertised by the update check (AssistantService.checkForAppUpdate). */
data class UpdateInfo(val version: String, val url: String)
