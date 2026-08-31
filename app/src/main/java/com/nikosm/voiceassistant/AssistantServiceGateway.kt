package com.nikosm.voiceassistant

import android.media.AudioAttributes
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.cert.X509Certificate
import okhttp3.RequestBody.Companion.asRequestBody

// ---------------------------------------------------------------------------
// On-device language detection for gateway TTS routing. Uses ML Kit's BUNDLED
// language-id model (ships in the APK, no network / Play Services dependency).
// ---------------------------------------------------------------------------
private val gatewayLanguageIdClient by lazy {
    LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.5f) // ML Kit default, stated explicitly for clarity
            .build()
    )
}

// BCP-47 code -> the app's internal language name. The values are the exact
// TRANSLATION_LANGUAGES entries (Models.kt) — the same name strings the gateway's
// LANG_CONFIG / UNSUPPORTED_TTS_LANGUAGES tables are keyed by.
private val bcp47ToLanguageName = mapOf(
    "en" to "English",
    "zh" to "Chinese",  // ML Kit may report region-qualified "zh-CN"/"zh-TW"; base-subtag lookup handles it
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "ja" to "Japanese",
    "pt" to "Portuguese",
    "ru" to "Russian",
    "it" to "Italian",
    "ko" to "Korean",
    "he" to "Hebrew",
    "iw" to "Hebrew",   // legacy ISO 639-1 code for Hebrew, some runtimes still emit it
    "el" to "Greek",
    "nl" to "Dutch",
    "tr" to "Turkish",
    "ar" to "Arabic",
    "hi" to "Hindi"
)

private fun mapBcp47ToLanguageName(code: String): String? {
    bcp47ToLanguageName[code]?.let { return it }
    // Region-qualified codes ("pt-BR", "zh-CN") resolve via the base subtag.
    return bcp47ToLanguageName[code.substringBefore('-').lowercase()]
}

/**
 * Detects the language of an LLM response on-device and maps it to the internal
 * language name the gateway expects.
 *
 * Fallback rules:
 *  - "und" (undetermined, e.g. text too short/ambiguous) -> "English" (per spec).
 *  - detection failure (exception) -> "English".
 *  - detected but unmapped code -> raw BCP-47 code passed through, so the gateway's
 *    UNSUPPORTED_TTS_LANGUAGES mechanism treats it as text-only instead of using a
 *    wrong voice.
 */
internal suspend fun detectGatewayResponseLanguage(text: String): String {
    return try {
        withContext(Dispatchers.IO) {
            val code = Tasks.await(gatewayLanguageIdClient.identifyLanguage(text))
            val mapped = mapBcp47ToLanguageName(code)
            // TODO(temporary debug logging — remove once TTS language routing is verified on-device)
            android.util.Log.d("AssistantService", "DEBUG: TTS language detection: len=${text.length} bcp47='$code' mapped='${mapped ?: "none"}'")
            when {
                mapped != null -> mapped
                code == "und" -> {
                    android.util.Log.d("AssistantService", "DEBUG: TTS language undetermined — falling back to 'English'")
                    "English"
                }
                else -> {
                    android.util.Log.d("AssistantService", "DEBUG: TTS language '$code' has no internal mapping — passing raw code to gateway (expect UNSUPPORTED_TTS_LANGUAGES text-only fallback)")
                    code
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("AssistantService", "DEBUG: TTS language detection failed — falling back to 'English'", e)
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
                
                if (!gw.username.isNullOrBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                }
                
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                    val contentType = response.header("Content-Type") ?: ""
                    if (contentType.contains("application/json")) {
                        null
                    } else {
                        response.body.bytes()
                    }
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
            
            if (!gw.username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
            }
            
            client.newCall(requestBuilder.build()).execute().use { response ->
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
        }
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "Gateway synthesis failed", e)
        null
    }
}

internal suspend fun AssistantService.transcribeWithGateway(file: File, persona: Persona): String? {
    val url = persona.backendUrl
    if (url.isBlank()) return null
    
    val gw = _serverBases.value.find { it.url == url } ?: ServerConfig("Gateway", url)
    
    return try {
        withContext(Dispatchers.IO) {
            val mediaType = if (file.extension == "wav") "audio/wav".toMediaType() else "audio/mp4".toMediaType()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", file.name, file.asRequestBody(mediaType))
                .build()
            
            val requestBuilder = Request.Builder()
                .url(url.trimEnd('/') + "/transcribe")
                .post(requestBody)
            
            if (!gw.username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
            }
            
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                val body = response.body.string()
                JSONObject(body).optString("text", "")
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "Gateway transcription failed", e)
        null
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
