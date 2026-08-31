package com.nikosm.voiceassistant

import android.media.AudioAttributes
import android.media.AudioManager
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
    val language = persona.targetLanguage
    
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
