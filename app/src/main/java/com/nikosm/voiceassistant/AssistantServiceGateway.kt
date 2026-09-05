package com.nikosm.voiceassistant

import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.cert.X509Certificate
import okhttp3.RequestBody.Companion.asRequestBody

// ---------------------------------------------------------------------------
// On-device language detection for gateway TTS routing. Zero-dependency
// Unicode-script heuristic — replaces ML Kit language-id, a proprietary
// dependency that reports telemetry to Firebase's logging backend roughly
// every 15 minutes regardless of Play Services presence. Deliberately detects
// only the non-Latin scripts that actually caused the original TTS-mislabeling
// bug; Latin-script languages fall through to the "English" default by design.
// ---------------------------------------------------------------------------

/**
 * Detects the dominant non-Latin script of [text] and returns the internal
 * language name the gateway expects (the exact TRANSLATION_LANGUAGES entries
 * the gateway's LANG_CONFIG / UNSUPPORTED_TTS_LANGUAGES tables are keyed by).
 * Returns null when no non-Latin script is sufficiently present — Latin-script
 * languages, undetermined text, and short/noise-only samples fall through to
 * the caller's default.
 */
private fun detectScriptLanguage(text: String): String? {
    val sample = text.take(200) // enough to judge dominant script
    val scriptCounts = mutableMapOf<String, Int>()
    for (ch in sample) {
        val lang = when {
            ch in '\u0370'..'\u03FF' -> "Greek"
            ch in '\u0400'..'\u04FF' -> "Russian"
            ch in '\u0590'..'\u05FF' -> "Hebrew"
            ch in '\u0600'..'\u06FF' -> "Arabic"
            ch in '\u4E00'..'\u9FFF' -> "Chinese"
            ch in '\u3040'..'\u30FF' -> "Japanese"
            ch in '\uAC00'..'\uD7AF' -> "Korean"
            ch in '\u0900'..'\u097F' -> "Hindi" // Devanagari — Hindi is a LANG_CONFIG language; omitting it would fall through to the English default (the original bug class)
            else -> null
        }
        if (lang != null) scriptCounts[lang] = (scriptCounts[lang] ?: 0) + 1
    }
    val dominant = scriptCounts.maxByOrNull { it.value }
    // Require a real presence of non-Latin characters, not just noise/punctuation
    return if (dominant != null && dominant.value >= 3) dominant.key else null
}

/**
 * Detects the language of an LLM response on-device for gateway TTS routing.
 *
 * Fallback rules (same contract as the previous ML Kit version):
 *  - no dominant non-Latin script (Latin-script languages, undetermined or
 *    too-short text) -> "English" (per spec).
 *  - detection failure (exception) -> "English".
 * The old "unmapped BCP-47 passthrough" branch is gone by construction: the
 * script heuristic only ever returns one of the seven supported non-Latin
 * language names, so no unsupported code can reach the gateway from here.
 */
internal suspend fun detectGatewayResponseLanguage(text: String): String {
    return try {
        val detected = detectScriptLanguage(text)
        detected ?: "English"
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) android.util.Log.w("AssistantService", "DEBUG: TTS language detection failed — falling back to 'English'", e)
        "English"
    }
}

internal fun AssistantService.testGatewayVoice(text: String, url: String, language: String, engine: String, kokoroVoice: String) {
    val gw = _serverBases.value.find { it.url == url } ?: ServerConfig("Test", url)
    serviceScope.launch {
        _state.value = AssistantState.THINKING
        updateNotification("Testing Gateway...")
        try {
            val bytes = withContext(Dispatchers.IO) {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text", text)
                    .addFormDataPart("language", language)
                    .addFormDataPart("engine", engine)
                    .apply {
                        if (engine == "kokoro" && kokoroVoice != "af_heart") {
                            addFormDataPart("voice", kokoroVoice)
                        }
                    }
                    .build()
                
                val requestBuilder = Request.Builder()
                    .url(url.trimEnd('/') + "/synthesize")
                    .post(requestBody)
                
                when (gw.effectiveAuthType) {
                    AuthType.NONE -> { /* no Authorization header */ }
                    AuthType.BASIC -> if (!gw.username.isNullOrBlank()) {
                        requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                    }
                    AuthType.API_KEY -> if (!gw.apiKey.isNullOrBlank()) {
                        requestBuilder.header("Authorization", "Bearer ${gw.apiKey}")
                    }
                }

                // Tracked in currentCall so the Stop button can cancel this call too.
                val call = client.newCall(requestBuilder.build())
                currentCall = call
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                        val contentType = response.header("Content-Type") ?: ""
                        if (contentType.contains("application/json")) {
                            null
                        } else {
                            response.body.bytes()
                        }
                    }
                } finally {
                    // A6-style: only clear the slot if this call still owns it.
                    if (currentCall === call) currentCall = null
                }
            }

            if (bytes != null) {
                val outFile = File(cacheDir, "test_synthesis.wav")
                outFile.writeBytes(bytes)
                playAudioFile(outFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("AssistantService", "Gateway test failed", e)
        } finally {
            if (_state.value == AssistantState.THINKING) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

internal suspend fun AssistantService.synthesizeWithGateway(text: String, persona: Persona): ByteArray? {
    if (text.isBlank()) return null
    val url = persona.backendUrl
    if (url.isBlank()) return null
    
    val gw = _serverBases.value.find { it.url == url } ?: ServerConfig("Gateway", url)
    // Non-Translator personas keep targetLanguage at its "English" default no matter
    // what language the model actually replied in, so the gateway would apply an
    // English voice to foreign text. Detect the response language on-device instead.
    // Translator personas translate TO persona.targetLanguage, so their explicit
    // value is authoritative and stays untouched.
    val language = if (persona.isTranslator) {
        persona.targetLanguage
    } else {
        detectGatewayResponseLanguage(text)
    }
    
    return try {
        withContext(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
                .addFormDataPart("language", language)
                .addFormDataPart("engine", persona.voiceEngine)
                .apply {
                    if (persona.voiceEngine == "kokoro" && persona.kokoroVoice != "af_heart") {
                        addFormDataPart("voice", persona.kokoroVoice)
                    }
                }
                .build()
            
            val requestBuilder = Request.Builder()
                .url(url.trimEnd('/') + "/synthesize")
                .post(requestBody)
            
            when (gw.effectiveAuthType) {
                AuthType.NONE -> { /* no Authorization header */ }
                AuthType.BASIC -> if (!gw.username.isNullOrBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                }
                AuthType.API_KEY -> if (!gw.apiKey.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${gw.apiKey}")
                }
            }

            // Tracked in currentCall so the Stop button can cancel synthesis too —
            // previously an in-flight synthesis was unstoppable and its audio played
            // even after Stop (the identity guard keeps cleanup from clobbering a
            // newer phase's call).
            val call = client.newCall(requestBuilder.build())
            currentCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                    val contentType = response.header("Content-Type") ?: ""
                    if (contentType.contains("application/json")) {
                        val json = JSONObject(response.body.string())
                        val note = json.optString("note", "Voice unavailable for this language.")
                        android.util.Log.d("AssistantService", "Synthesis skipped: $note")
                        null
                    } else {
                        response.body.bytes()
                    }
                }
            } finally {
                if (currentCall === call) currentCall = null
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "Gateway synthesis failed", e)
        null
    }
}

internal suspend fun AssistantService.transcribeWithGateway(file: File, persona: Persona): String? {
    val allGateways = _serverBases.value

    // S2: resolve preferred gateway from persona's configured backendUrl
    val preferredGateway = if (persona.backendUrl.isNotBlank()) {
        allGateways.find { it.url == persona.backendUrl }
    } else null

    // Build gwsToTry using the same failover logic as sendTextMessageToServer/sendAudioToServer
    val gwsToTry: List<ServerConfig> = if (persona.allowGatewayFailover) {
        val workingGateways = allGateways.filter { isServerHealthyForRetry(it.name) }
        buildList {
            preferredGateway?.let { add(it) }
            addAll(workingGateways.filter { it != preferredGateway })
        }
    } else {
        when {
            preferredGateway != null -> listOf(preferredGateway)
            persona.backendUrl.isBlank() -> throw Exception(
                "No gateway is configured for persona '${persona.name}'. Set a Backend URL in its persona settings, or enable 'Allow Gateway Failover'."
            )
            else -> throw Exception(
                "Gateway '${persona.backendUrl}' for persona '${persona.name}' is not in the configured gateway list. Check Settings, or enable 'Allow Gateway Failover'."
            )
        }
    }

    if (gwsToTry.isEmpty()) {
        throw Exception("No working gateways available for transcription.")
    }

    var lastEx: Exception? = null
    for (gw in gwsToTry.distinct()) {
        val url = gw.url
        try {
            val result = withContext(Dispatchers.IO) {
                val mediaType = if (file.extension == "wav") "audio/wav".toMediaType() else "audio/mp4".toMediaType()
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("audio", file.name, file.asRequestBody(mediaType))
                    .build()

                val requestBuilder = Request.Builder()
                    .url(url.trimEnd('/') + "/transcribe")
                    .post(requestBody)

                when (gw.effectiveAuthType) {
                    AuthType.NONE -> { /* no Authorization header */ }
                    AuthType.BASIC -> if (!gw.username.isNullOrBlank()) {
                        requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                    }
                    AuthType.API_KEY -> if (!gw.apiKey.isNullOrBlank()) {
                        requestBuilder.header("Authorization", "Bearer ${gw.apiKey}")
                    }
                }

                val call = client.newCall(requestBuilder.build())
                currentCall = call
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                        val body = response.body.string()
                        JSONObject(body).optString("text", "")
                    }
                } catch (e: Exception) {
                    // A5: a user stop cancelled this attempt — abort the failover loop
                    // immediately and don't mark the gateway unhealthy for a cancellation
                    // it didn't cause (mirrors the tracked loops in AssistantServiceChat).
                    if (call.isCanceled()) throw java.io.IOException("Cancelled")
                    throw e
                } finally {
                    if (currentCall === call) currentCall = null
                }
            }

            // Update status to working since we just had a successful call
            withContext(Dispatchers.Main) {
                val statusMap = _serverStatus.value.toMutableMap()
                _serverBases.value.find { it.url == url }?.let { statusMap[it.url] = "Online" }
                _serverStatus.value = statusMap
            }

            if (result.isNotBlank()) return result
            throw Exception("Empty transcription result")
        } catch (e: Exception) {
            // Mark failed with cooldown
            withContext(Dispatchers.Main) {
                val statusMap = _serverStatus.value.toMutableMap()
                _serverBases.value.find { it.url == url }?.let { cfg ->
                    val failureLabel = if (e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                        "connection/timeout"
                    } else {
                        e.message?.take(30)
                    }
                    statusMap[cfg.url] = "failed: $failureLabel"
                    serverFailCooldownUntilMillis[cfg.url] = System.currentTimeMillis() + FAILED_COOLDOWN_MS
                }
                _serverStatus.value = statusMap
            }
            lastEx = e
        }
    }

    throw lastEx ?: Exception("All transcription attempts failed")
}

// ---------------------------------------------------------------------------
// Local RAG microservice integration — upload documents and check collection
// size. The service runs alongside the gateway (same network) and is secured
// with a self-signed cert + HTTP Basic Auth, so these calls go through the
// TOFU-pinning `client` (same category as gateway/TTS infrastructure — first
// connection triggers the standard cert-approval prompt) and authenticate with
// the RAG service's own dedicated credentials stored in settings.
// ---------------------------------------------------------------------------
internal sealed class RagResult<out T> {
    data class Success<T>(val value: T) : RagResult<T>()
    data class Failure(val exception: Exception) : RagResult<Nothing>()
}

// Builds a request to the RAG service: normalizes the stored URL (defaulting
// to https:// — the service no longer accepts plain HTTP) and attaches the
// RAG service's dedicated Basic-Auth credentials. Those live in their own
// settings fields, decoupled from the gateway entries: the RAG server (:8882)
// and the TTS gateway (:8880) are different endpoints even on the same host,
// so URL-matching against _serverBases could never resolve the right creds.
private fun AssistantService.buildRagRequestBuilder(ragServerUrl: String, path: String): Request.Builder {
    val raw = ragServerUrl.trim()
    val base = if (raw.startsWith("http")) raw.trimEnd('/') else "https://${raw.trimEnd('/')}"
    val builder = Request.Builder().url(base + path)
    val user = settingsManager.getRagUsername()
    if (!user.isNullOrBlank()) {
        builder.header("Authorization", Credentials.basic(user, settingsManager.getRagPassword() ?: ""))
    }
    return builder
}

internal suspend fun AssistantService.uploadKnowledgeDocument(
    text: String,
    sourceName: String,
    ragServerUrl: String
): RagResult<Unit> = withContext(Dispatchers.IO) {
    val body = okhttp3.FormBody.Builder()
        .add("text", text)
        .add("source_name", sourceName)
        .build()
    val request = buildRagRequestBuilder(ragServerUrl, "/ingest").post(body).build()
    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                RagResult.Failure(Exception("RAG server returned HTTP ${response.code}: ${response.message}"))
            } else {
                RagResult.Success(Unit)
            }
        }
    } catch (e: Exception) {
        RagResult.Failure(Exception("Failed to upload document to RAG server: ${e.message}", e))
    }
}

// Fire-and-forget RAG ingest on serviceScope: the upload must survive Settings-dialog
// closure (the dialog-scoped rememberCoroutineScope used to cancel it mid-flight — the
// HTTP request usually completed server-side, but the app-side outcome was silently
// discarded). Progress/outcome is published via knowledgeUploadStatus, which the dialog
// collects for display and which survives closure, so reopening Settings still shows
// how each document ended up.
internal fun AssistantService.uploadKnowledgeDocumentsToRag(uris: List<Uri>, ragUrl: String) {
    uris.forEach { uri ->
        serviceScope.launch {
            val name = uri.path?.substringAfterLast("/", "document")?.substringAfterLast(".") ?: "document"
            try {
                _knowledgeUploadStatus.value = "Uploading $name..."
                val text = readAttachmentText(uri)
                when (val result = uploadKnowledgeDocument(text, name, ragUrl)) {
                    is RagResult.Success -> _knowledgeUploadStatus.value = "Uploaded $name"
                    is RagResult.Failure -> _knowledgeUploadStatus.value = "Failed: ${result.exception.message}"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _knowledgeUploadStatus.value = "Failed: ${e.message}"
            }
        }
    }
}

internal suspend fun AssistantService.getKnowledgeCount(
    ragServerUrl: String
): RagResult<Int> = withContext(Dispatchers.IO) {
    val request = buildRagRequestBuilder(ragServerUrl, "/health").get().build()
    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                RagResult.Failure(Exception("RAG server health check failed: HTTP ${response.code}"))
            } else {
                val bodyStr = response.body.string()
                val count = try {
                    JSONObject(bodyStr).optInt("documents_in_collection", 0)
                } catch (e: Exception) {
                    bodyStr.toIntOrNull() ?: 0
                }
                RagResult.Success(count)
            }
        }
    } catch (e: Exception) {
        RagResult.Failure(Exception("Failed to query RAG server: ${e.message}", e))
    }
}

// Step 3 — RAG retrieval: fetch the top matches for the user's query from the
// knowledge base. Same graceful-failure contract as fetchWebSearchContext:
// returns "" when unconfigured, on HTTP failure, or on any exception, so the
// chat proceeds normally without the injected context.
internal suspend fun AssistantService.fetchRagContext(query: String): String {
    val ragUrl = settingsManager.getRagServerUrl()
    if (ragUrl.isNullOrBlank()) return ""
    return try {
        withContext(Dispatchers.IO) {
            val requestBuilder = buildRagRequestBuilder(ragUrl, "/retrieve")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("query", query)
                .addFormDataPart("top_k", "3")
                .build()
            requestBuilder.post(body)
            // Tracked in currentCall so context enrichment is stoppable too.
            val call = client.newCall(requestBuilder.build())
            currentCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@withContext ""
                    val json = JSONObject(response.body.string())
                    val results = json.optJSONArray("results") ?: return@withContext ""
                    if (results.length() == 0) return@withContext ""
                    val sb = StringBuilder("RELEVANT INFORMATION FROM YOUR KNOWLEDGE BASE:\n")
                    for (i in 0 until results.length()) {
                        val r = results.getJSONObject(i)
                        sb.append("- ${r.optString("text")} (source: ${r.optString("source")})\n")
                    }
                    sb.append("\nUse this information if relevant to answer the user's question.")
                    sb.toString()
                }
            } finally {
                if (currentCall === call) currentCall = null
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "RAG retrieval failed", e)
        ""
    }
}

fun AssistantService.approveCertificate(request: CertApprovalRequest) {
    val certs = settingsManager.getTrustedCertificates().toMutableMap()
    certs[request.host] = request.fingerprint
    settingsManager.saveTrustedCertificates(certs)
    _pendingCertApproval.value = null
}

fun AssistantService.denyCertificate() {
    _pendingCertApproval.value = null
}

internal fun AssistantService.sha256Fingerprint(cert: X509Certificate): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(cert.encoded).joinToString(":") { "%02X".format(it) }
}
