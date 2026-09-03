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
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.annotation.RequiresPermission
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
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

    private var currentPersonaName: String? = null

    // A2/B1: monotonically increasing chat request sequence. Bumped every time a new
    // chat request starts; a response may only be APPLIED if it belongs to the most
    // recent request (isChatRequestCurrent) AND the active persona is unchanged
    // (isChatContextCurrent). State ownership (catch handling / finally resets)
    // follows the sequence only, so a persona switch mid-request still cleans up
    // state without polluting the new persona's history.
    private var chatRequestSeq: Long = 0
    internal fun nextChatRequestSeq(): Long = ++chatRequestSeq
    internal fun isChatRequestCurrent(seq: Long): Boolean = seq == chatRequestSeq
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

    val _lastPriceSyncTimestamp = MutableStateFlow(0L)
    val lastPriceSyncTimestamp = _lastPriceSyncTimestamp.asStateFlow()

    internal val _pendingCertApproval = MutableStateFlow<CertApprovalRequest?>(null)
    val pendingCertApproval = _pendingCertApproval.asStateFlow()

    private val _handsFreeMode = MutableStateFlow(false)
    val handsFreeMode = _handsFreeMode.asStateFlow()

    lateinit var tts: TextToSpeech
    var ttsReady = false
    private var _espeakEngine: EspeakEngine? = null
    val espeakEngine: EspeakEngine?
        get() {
            if (_espeakEngine == null) {
                try {
                    _espeakEngine = EspeakEngine(this)
                } catch (e: Throwable) {
                    android.util.Log.e("AssistantService", "Failed to init eSpeak NG: ${e.message}")
                }
            }
            return _espeakEngine
        }

    lateinit var audioManager: AudioManager
    var audioFocusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false

    val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (currentPlayer?.isPlaying == true) {
                    currentPlayer?.pause()
                    pausedByFocusLoss = true
                    _state.value = AssistantState.IDLE
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    currentPlayer?.start()
                    pausedByFocusLoss = false
                    _state.value = AssistantState.SPEAKING
                }
            }
        }
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    var currentPlayer: MediaPlayer? = null
    var currentCall: Call? = null
    var currentAudioTrack: AudioTrack? = null
    // A3: utterance ID issued by the most recent speakTextOnDevice() call. TTS
    // onDone/onError callbacks only run their cleanup when their delivered ID still
    // matches this, so a stale callback from an older utterance can't cut off a
    // newer one that is still speaking.
    var currentUtteranceId: String? = null

    fun toggleSilence() {
        _silenced.value = !_silenced.value
        currentPlayer?.setVolume(if (_silenced.value) 0f else 1f, if (_silenced.value) 0f else 1f)
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
                if (state == AssistantState.IDLE) {
                    vadRecorder?.resume()
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

        try {
            val requestBuilder = Request.Builder().url(url)
            authorizationHeader?.let { requestBuilder.header("Authorization", it) }

            fastClient.newCall(requestBuilder.build()).execute().use { response ->
                val statusMap = _serverStatus.value.toMutableMap()
                if (response.isSuccessful || response.code == 401) {
                    statusMap[target.name] = "Online"
                } else {
                    statusMap[target.name] = "failed: ${response.code}"
                }
                _serverStatus.value = statusMap
            }
        } catch (e: Exception) {
            val statusMap = _serverStatus.value.toMutableMap()
            statusMap[target.name] = "failed: offline"
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

        settingsManager.getMessages()?.let {
            // Optional: Migrate global messages to the first persona if it has no history?
            // For now, just keep per-persona logic clean.
        }

        _ollamaBaseUrls.value = settingsManager.getOllamaBases() ?: emptyList()
        _totalCost.value = settingsManager.getTotalCost()
        _favoriteModels.value = settingsManager.getFavoriteModels() ?: emptyList()
        _lastPriceSyncTimestamp.value = settingsManager.getLastPriceSyncTimestamp()

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
            current[index] = persona
            _personas.value = current
            saveSettings()
        }
    }

    fun removePersona(index: Int) {
        val current = _personas.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
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
    fun importBackup(json: String): Boolean {
        val success = settingsManager.importBackup(json)
        if (success) loadSettings()
        return success
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
        vadRecorder?.stop()
        vadDetector?.close()
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
        try {
            recorder?.apply { 
                stop()
                release() 
            }
        } catch (e: Exception) {
            android.util.Log.e("AssistantService", "Recorder stop failed", e)
        } finally {
            recorder = null
        }
        outputFile?.let { sendAudioToServer(it, currentPersona) }
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
        // Save current persona history before switching
        currentPersonaName?.let { oldName ->
            settingsManager.savePersonaMessages(oldName, _messages.value)
        }
        
        currentPersonaName = persona.name
        _messages.value = settingsManager.getPersonaMessages(persona.name) ?: emptyList()
    }

    fun clearMessages() { 
        _messages.value = emptyList()
        currentPersonaName?.let { settingsManager.savePersonaMessages(it, emptyList()) }
        saveSettings() 
    }
    fun removeMessagesFrom(index: Int) { _messages.value = _messages.value.take(index); saveSettings() }
    
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
        if (api.apiKey.isBlank() && api.icon != "C") return
        _isLoadingModels.value = true
        val baseUrl = api.baseUrl.trim().removeSuffix("/")
        // TODO(temporary debug logging — remove once the DeepSeek model list is verified)
        android.util.Log.d("AssistantService", "DEBUG fetchCloudModels: name='${api.name}' icon='${api.icon}' baseUrl=$baseUrl")
        serviceScope.launch(Dispatchers.IO) {
            val statusMap = _serverStatus.value.toMutableMap()
            statusMap.remove(api.name)
            try {
                val models = when (api.icon) {
                    "G" -> { // Google
                        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${api.apiKey}"
                        standardClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) {
                                statusMap[api.name] = "Google API Error: ${response.code}"
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
                                statusMap[api.name] = "Anthropic Error: ${response.code}"
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
                                statusMap[api.name] = when(response.code) {
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

                            // TODO(temporary debug logging — remove once the DeepSeek model list is verified)
                            android.util.Log.d("AssistantService", "DEBUG raw models from API for '${api.name}' (icon='${api.icon}'): ${rawModels.size} models -> ${rawModels.joinToString(", ") { it.optString("id") }}")

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
                // TODO(temporary debug logging — remove once the DeepSeek model list is verified)
                android.util.Log.d("AssistantService", "DEBUG final stored model list for '${api.name}': $models")
                withContext(Dispatchers.Main) {
                    val current = _fetchedCloudModels.value.toMutableMap()
                    current[api.name] = models
                    _fetchedCloudModels.value = current
                    _serverStatus.value = statusMap
                }
            } catch (e: Exception) {
                statusMap[api.name] = e.message ?: "Cloud fetch failed"
                withContext(Dispatchers.Main) { _serverStatus.value = statusMap }
            } finally {
                _isLoadingModels.value = false
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

        val persona = personas.value.firstOrNull() ?: DEFAULT_PERSONAS[0]

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
                    sendAudioToServer(file, persona)
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
