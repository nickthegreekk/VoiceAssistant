package com.nikosm.voiceassistant

import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal fun AssistantService.sendAudioToServer(file: File, currentPersona: Persona) {
    if (currentPersona.model.isBlank()) {
        _messages.value = _messages.value + ChatMessage("assistant", "Please choose a model for this persona in its settings.", isError = true)
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    serviceScope.launch {
        val startTime = System.currentTimeMillis()
        _state.value = AssistantState.THINKING
        updateNotification("Thinking...")

        val useDeviceVoice = currentPersona.voiceMode != VoiceMode.GATEWAY

        if (currentPersona.isCloud && isCloudModel(currentPersona.model)) {
            serviceScope.launch {
                try {
                    val transcribedText = transcribeWithGateway(file, currentPersona)
                        ?: throw Exception("Could not transcribe audio. Check Gateway connection.")
                    performCloudChat(transcribedText, currentPersona, useDeviceVoice, startTime, currentTurnInHistory = false)
                } catch (e: Exception) {
                    android.util.Log.e("AssistantService", "Cloud voice chat failed", e)
                    _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message}", isError = true)
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                }
            }
            return@launch
        }

        try {
            val responseData = withContext(Dispatchers.IO) {
                val rawModel = currentPersona.model
                val (displayServer, actualModel) = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
                    rawModel.substring(1, rawModel.indexOf("]")) to rawModel.substringAfter("] ")
                } else {
                    null to rawModel
                }

                val rawBUrl = currentPersona.backendUrl.ifBlank {
                    displayServer?.let { name -> _ollamaBaseUrls.value.find { it.name == name }?.url } ?: ""
                }
                // Guard: Never send a gateway URL (8880) as a backend_url parameter
                val bUrl = if (rawBUrl.isBlank() || rawBUrl.contains(":8880")) "" else rawBUrl.trim().removeSuffix("/")

                // IF IT'S A DIRECT OLLAMA CALL WITH VOICE INPUT
                if (displayServer != null) {
                     val ollamaBase = _ollamaBaseUrls.value.find { it.name == displayServer }?.url
                     if (ollamaBase != null) {
                         // 1. Transcribe via Gateway
                         val transcribedText = transcribeWithGateway(file, currentPersona)
                             ?: throw Exception("Could not transcribe audio. Check Gateway connection.")

                         // 2. Chat with Ollama
                         val directRes = performDirectOllamaChat(ollamaBase, actualModel, transcribedText, currentPersona, currentTurnInHistory = false)

                         // 3. Handle Voice via Gateway if needed
                         var audioBytes: ByteArray? = null
                         if (currentPersona.voiceMode == VoiceMode.GATEWAY) {
                             audioBytes = synthesizeWithGateway(directRes.first, currentPersona)
                         }

                         return@withContext listOf(transcribedText, directRes.first, directRes.second, audioBytes)
                     }
                }

                // ELSE: CLOUD OR GATEWAY CALL (Existing combined logic)
                val mediaType = if (file.extension == "wav") "audio/wav".toMediaType() else "audio/mp4".toMediaType()

                val currentDateTime = getCurrentDateTimeString()
                val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    addFormDataPart("audio", file.name, file.asRequestBody(mediaType))
                    addFormDataPart("model", actualModel)
                    addFormDataPart("backend_url", bUrl)
                    addFormDataPart("temperature", currentPersona.temperature.toString())
                    addFormDataPart("top_p", currentPersona.topP.toString())
                    addFormDataPart("top_k", currentPersona.topK.toString())
                    addFormDataPart("repeat_penalty", currentPersona.repeatPenalty.toString())
                    addFormDataPart("num_ctx", "8192")
                    addFormDataPart("context_time", currentDateTime)
                    if (currentPersona.isTranslator) {
                        addFormDataPart("target_language", currentPersona.targetLanguage)
                    }
                }.build()

                val statusMap = _serverStatus.value
                val allGateways = _serverBases.value

                // If useDeviceVoice is true, we still need a gateway for STT.
                // We'll try to find any working gateway.
                val preferredGateway = if (currentPersona.backendUrl.isNotBlank()) {
                    allGateways.find { it.name == displayServer || it.url == currentPersona.backendUrl }
                } else null

                val workingGateways = allGateways.filter {
                    val status = statusMap[it.name]
                    status == null || !status.lowercase().contains("failed")
                }

                val gwsToTry = mutableListOf<ServerConfig>()
                preferredGateway?.let { gwsToTry.add(it) }
                gwsToTry.addAll(workingGateways.filter { it != preferredGateway })

                if (gwsToTry.isEmpty()) {
                    throw Exception("No working gateways available for voice processing.")
                }

                var lastEx: Exception? = null
                for (gw in gwsToTry.distinct()) {
                    val base = gw.url
                    // Scale read timeout with maxTokens to handle long processing/reasoning
                    val currentClient = getDynamicClient(currentPersona, useStandard = false)

                    val requestBuilder = Request.Builder()
                        .url(if (currentPersona.isTranslator) "$base/translate" else "$base/voice-chat")
                        .post(requestBody)

                    if (!gw.username.isNullOrBlank()) {
                        requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                    }

                    val call = currentClient.newCall(requestBuilder.build())
                    currentCall = call
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw Exception("Server error: ${response.code}")

                            val contentType = response.header("Content-Type") ?: ""
                            val uText = response.decodeTextHeader("X-User-Text-B64", "(voice message)")
                            _lastWorkingBase.value = base

                            // Update status to working since we just had a successful call
                            withContext(Dispatchers.Main) {
                                val statusMap = _serverStatus.value.toMutableMap()
                                _serverBases.value.find { it.url == base }?.let { statusMap[it.name] = "Online" }
                                _serverStatus.value = statusMap
                            }

                            if (contentType.contains("application/json")) {
                                val body = response.body.string()
                                val json = JSONObject(body)
                                val rText = json.optString("translated_text", "")
                                val note = json.optString("note", "")
                                val finalRText = if (note.isNotEmpty()) "$rText\n\n($note)" else rText
                                return@withContext listOf(uText, finalRText.ifBlank { "..." }, null, null)
                            } else {
                                val rText = response.decodeTextHeader("X-Translated-Text-B64",
                                    response.decodeTextHeader("X-Response-Text-B64", "..."))
                                val bytes = response.body.bytes()
                                return@withContext listOf(uText, rText, null, if (useDeviceVoice) null else bytes)
                            }
                        }
                    } catch (e: Exception) {
                        if (call.isCanceled()) throw java.io.IOException("Cancelled")

                        // Mark server as failed in background so we don't try it again soon
                        withContext(Dispatchers.Main) {
                            val statusMap = _serverStatus.value.toMutableMap()
                            _serverBases.value.find { it.url == base }?.let { statusMap[it.name] = "failed: ${e.message?.take(30)}..." }
                            _serverStatus.value = statusMap
                        }

                        lastEx = e
                    }
                }
                throw lastEx ?: Exception("All connection attempts failed")
            }
            val (uText, rText, reasoning, bytes) = responseData as List<Any?>
            val responseTimeMs = System.currentTimeMillis() - startTime
            val audioPath = if (bytes != null && !useDeviceVoice) {
                val outFile = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(bytes as ByteArray)
                outFile.absolutePath
            } else null

            _messages.value = _messages.value +
                ChatMessage("user", uText as String) +
                ChatMessage("assistant", rText as String, reasoning as? String, audioFilePath = audioPath, responseTimeMs = responseTimeMs)
            saveSettings()

            if (useDeviceVoice) {
                playResponse(currentPersona, deviceText = rText as String)
            } else if (audioPath != null) {
                playResponse(currentPersona, file = File(audioPath))
            }
        } catch (e: Exception) {
            android.util.Log.e("AssistantService", "Chat failed", e)
            _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message ?: "Unknown error"}", isError = true)
            currentCall = null
        } finally {
            if (_state.value == AssistantState.THINKING) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

internal fun AssistantService.sendTextMessageToServer(inputText: String, currentPersona: Persona, attachments: List<Uri> = emptyList()) {
    if (inputText.isBlank() && attachments.isEmpty()) return
    val startTime = System.currentTimeMillis()
    _voiceDuration.value = 0
    _messages.value = _messages.value + ChatMessage("user", if (attachments.isNotEmpty()) "$inputText [${attachments.size} files]" else inputText)
    saveSettings()

    if (currentPersona.model.isBlank()) {
        _messages.value = _messages.value + ChatMessage("assistant", "Please choose a model for this persona in its settings.", isError = true)
        return
    }

    val useDeviceVoice = currentPersona.voiceMode != VoiceMode.GATEWAY

    if (currentPersona.isCloud && isCloudModel(currentPersona.model)) {
        performCloudChat(inputText, currentPersona, useDeviceVoice, startTime, currentTurnInHistory = true)
        return
    }
    serviceScope.launch {
        _state.value = AssistantState.THINKING
        updateNotification("Thinking...")
        try {
            val responseData = withContext(Dispatchers.IO) {
                val rawModel = currentPersona.model
                val (displayServer, actualModel) = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
                    rawModel.substring(1, rawModel.indexOf("]")) to rawModel.substringAfter("] ")
                } else {
                    null to rawModel
                }

                val rawBUrl = currentPersona.backendUrl.ifBlank {
                    displayServer?.let { name -> _ollamaBaseUrls.value.find { it.name == name }?.url } ?: ""
                }
                // Guard: Never send a gateway URL (8880) as a backend_url parameter
                val bUrl = if (rawBUrl.isBlank() || rawBUrl.contains(":8880")) "" else rawBUrl.trim().removeSuffix("/")

                // If it's a direct Ollama call, we can call Ollama directly!
                if (displayServer != null) {
                     val ollamaBase = _ollamaBaseUrls.value.find { it.name == displayServer }?.url
                     if (ollamaBase != null) {
                         val directRes = performDirectOllamaChat(ollamaBase, actualModel, inputText, currentPersona, currentTurnInHistory = true)

                         // If it's a gateway voice mode, we need to fetch audio separately
                         if (currentPersona.voiceMode == VoiceMode.GATEWAY) {
                             val audioBytes = synthesizeWithGateway(directRes.first, currentPersona)
                             return@withContext Triple(directRes.first, directRes.second, audioBytes)
                         }

                         return@withContext directRes
                     }
                }

                val currentDateTime = getCurrentDateTimeString()
                val requestBuilder = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    addFormDataPart("text", "Current date and time: $currentDateTime\n\nQuestion: $inputText")
                    addFormDataPart("model", actualModel)
                    addFormDataPart("backend_url", bUrl)
                    addFormDataPart("temperature", currentPersona.temperature.toString())
                    addFormDataPart("top_p", currentPersona.topP.toString())
                    addFormDataPart("top_k", currentPersona.topK.toString())
                    addFormDataPart("repeat_penalty", currentPersona.repeatPenalty.toString())
                    addFormDataPart("num_ctx", "8192")
                    if (currentPersona.isTranslator) {
                        addFormDataPart("target_language", currentPersona.targetLanguage)
                    }
                }

                attachments.forEachIndexed { index, uri ->
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readBytes()
                            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                            val fileName = "file_$index"
                            requestBuilder.addFormDataPart("files", fileName, bytes.toRequestBody(mimeType.toMediaType()))
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VoiceAssistant", "Failed to read attachment: $uri", e)
                    }
                }

                val requestBody = requestBuilder.build()

                val statusMap = _serverStatus.value
                val allGateways = _serverBases.value

                val preferredGateway = if (currentPersona.backendUrl.isNotBlank()) {
                    allGateways.find { it.name == displayServer || it.url == currentPersona.backendUrl }
                } else null

                val workingGateways = allGateways.filter {
                    val status = statusMap[it.name]
                    status == null || !status.lowercase().contains("failed")
                }

                val gwsToTry = mutableListOf<ServerConfig>()
                preferredGateway?.let { gwsToTry.add(it) }
                gwsToTry.addAll(workingGateways.filter { it != preferredGateway })

                if (gwsToTry.isEmpty()) {
                    throw Exception("No working servers available. Check your connection in Settings.")
                }

                var lastEx: Exception? = null
                for (gw in gwsToTry.distinct()) {
                    val base = gw.url
                    // Scale read timeout with maxTokens to handle long processing/reasoning
                    val currentClient = getDynamicClient(currentPersona, useStandard = false)

                    val searchContext = if (isNewsRequest(inputText)) {
                        fetchNewsContext()
                    } else if (currentPersona.webSearchEnabled) {
                        fetchWebSearchContext(inputText)
                    } else ""

                    val finalRequestBody = if (searchContext.isNotEmpty()) {
                        val promptContext = "$searchContext\n\n"

                        // Rebuild request body with search context PREPENDED to input text
                        MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                            addFormDataPart("text", "${promptContext}Current date and time: $currentDateTime\n\nQuestion: $inputText")
                            addFormDataPart("model", actualModel)
                            addFormDataPart("backend_url", bUrl)
                            addFormDataPart("temperature", currentPersona.temperature.toString())
                            addFormDataPart("top_p", currentPersona.topP.toString())
                            addFormDataPart("top_k", currentPersona.topK.toString())
                            addFormDataPart("repeat_penalty", currentPersona.repeatPenalty.toString())
                            addFormDataPart("num_ctx", "8192")

                            if (currentPersona.isTranslator) {
                                addFormDataPart("target_language", currentPersona.targetLanguage)
                            }

                            // Re-add attachments
                            attachments.forEachIndexed { index, uri ->
                                try {
                                    contentResolver.openInputStream(uri)?.use { input ->
                                        val bytes = input.readBytes()
                                        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                                        val fileName = "file_$index"
                                        addFormDataPart("files", fileName, bytes.toRequestBody(mimeType.toMediaType()))
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("VoiceAssistant", "Failed to read attachment: $uri", e)
                                }
                            }
                        }.build()
                    } else requestBody

                    val requestBuilder = Request.Builder()
                        .url(if (currentPersona.isTranslator) "$base/translate" else "$base/text-chat")
                        .post(finalRequestBody)

                    if (!gw.username.isNullOrBlank()) {
                        requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                    }

                    val call = currentClient.newCall(requestBuilder.build())
                    currentCall = call
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw Exception("Server error: ${response.code}")

                            val contentType = response.header("Content-Type") ?: ""
                            _lastWorkingBase.value = base

                            // Update status to working
                            withContext(Dispatchers.Main) {
                                val statusMap = _serverStatus.value.toMutableMap()
                                val matched = _serverBases.value.find { it.url == base } ?:
                                              _ollamaBaseUrls.value.find { it.url == base }
                                matched?.let { statusMap[it.name] = "Online" }
                                _serverStatus.value = statusMap
                            }

                            if (contentType.contains("application/json")) {
                                val body = response.body.string()
                                val json = JSONObject(body)
                                // The /translate JSON shape has 'translated_text'
                                val rText = if (json.has("translated_text")) {
                                    val txt = json.getString("translated_text")
                                    val note = json.optString("note", "")
                                    if (note.isNotEmpty()) "$txt\n\n($note)" else txt
                                } else {
                                    response.decodeTextHeader("X-Response-Text-B64", "...")
                                }
                                return@withContext Triple(rText, null, null)
                            } else {
                                val rText = response.decodeTextHeader("X-Translated-Text-B64",
                                    response.decodeTextHeader("X-Response-Text-B64", "..."))
                                val bytes = response.body.bytes()
                                return@withContext Triple(rText, null, if (useDeviceVoice) null else bytes)
                            }
                        }
                    } catch (e: Exception) {
                        if (call.isCanceled()) throw java.io.IOException("Cancelled")

                        // Mark server as failed
                        withContext(Dispatchers.Main) {
                            val statusMap = _serverStatus.value.toMutableMap()
                            val matched = _serverBases.value.find { it.url == base } ?:
                                          _ollamaBaseUrls.value.find { it.name == base || it.url == base }
                            matched?.let { statusMap[it.name] = "failed: ${e.message?.take(30)}..." }
                            _serverStatus.value = statusMap
                        }

                        lastEx = e
                    }
                }
                throw lastEx ?: Exception("All connection attempts failed")
            }
            val (rText, reasoning, bytes) = responseData as Triple<String, String?, ByteArray?>
            val responseTimeMs = System.currentTimeMillis() - startTime
            val audioPath = if (bytes != null && !useDeviceVoice) {
                val outFile = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(bytes)
                outFile.absolutePath
            } else null

            _messages.value = _messages.value + ChatMessage("assistant", rText, reasoning, audioFilePath = audioPath, responseTimeMs = responseTimeMs)
            saveSettings()

            if (useDeviceVoice) {
                playResponse(currentPersona, deviceText = rText)
            } else if (audioPath != null) {
                playResponse(currentPersona, file = File(audioPath))
            }
        } catch (e: Exception) {
            android.util.Log.e("AssistantService", "Chat failed", e)
            _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message ?: "Unknown error"}", isError = true)
            currentCall = null
        } finally {
            if (_state.value == AssistantState.THINKING) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

internal fun AssistantService.fetchModels(config: ServerConfig? = null) {
    val targets = if (config != null) listOf(config) else _ollamaBaseUrls.value
    if (targets.isEmpty()) return
    _isLoadingModels.value = true

    serviceScope.launch(Dispatchers.IO) {
        val statusMap = _serverStatus.value.toMutableMap()
        val localModelsMap = _fetchedLocalModels.value.toMutableMap()

        for (target in targets) {
            statusMap.remove(target.name)
            var base = target.url.trim().removeSuffix("/")
            if (base.endsWith("/v1")) base = base.removeSuffix("/v1")
            if (base.endsWith("/api")) base = base.removeSuffix("/api")

            val endpoints = listOf("$base/api/tags", "$base/v1/models")
            var success = false
            var lastErrorMessage = "Could not connect to server"

            for (url in endpoints) {
                if (success) break
                try {
                    val requestBuilder = Request.Builder().url(url)
                    if (!target.username.isNullOrBlank()) {
                        requestBuilder.header("Authorization", Credentials.basic(target.username, target.password ?: ""))
                    }

                    fastClient.newCall(requestBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body.string()
                            val json = JSONObject(body)
                            val serverModels = mutableListOf<String>()

                            if (url.endsWith("/api/tags")) {
                                val modelsArray = json.optJSONArray("models")
                                if (modelsArray != null) {
                                    for (i in 0 until modelsArray.length()) {
                                        serverModels.add(modelsArray.getJSONObject(i).getString("name"))
                                    }
                                    success = true
                                }
                            } else {
                                val dataArray = json.optJSONArray("data")
                                if (dataArray != null) {
                                    for (i in 0 until dataArray.length()) {
                                        serverModels.add(dataArray.getJSONObject(i).getString("id"))
                                    }
                                    success = true
                                }
                            }
                            if (success) {
                                localModelsMap[target.name] = serverModels.map { "[${target.name}] $it" }
                                statusMap[target.name] = "Online"
                            }
                        } else {
                            lastErrorMessage = when (response.code) {
                                401 -> "Unauthorized"
                                else -> "Server error: ${response.code}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastErrorMessage = "failed: offline"
                }
            }
            if (!success) {
                statusMap[target.name] = lastErrorMessage
            }
        }

        withContext(Dispatchers.Main) {
            _serverStatus.value = statusMap
            _fetchedLocalModels.value = localModelsMap
            _availableModels.value = localModelsMap.values.flatten().distinct()
            _isLoadingModels.value = false
        }
    }
}

private fun AssistantService.isCloudModel(model: String): Boolean {
    val m = model.trim()
    if (!m.startsWith("[") || !m.contains("] ")) return false
    val providerName = m.substring(1, m.indexOf("]"))

    // Check if the provider name matches any of our registered cloud/custom APIs
    return _cloudApis.value.any { it.name == providerName } ||
           _customCloudApis.value.any { it.name == providerName }
}

private fun AssistantService.performCloudChat(text: String, persona: Persona, useDeviceVoice: Boolean = false, startTime: Long, currentTurnInHistory: Boolean) {
    serviceScope.launch {
        _state.value = AssistantState.THINKING
        updateNotification("Thinking (Cloud)...")
        try {
            val responseData = withContext(Dispatchers.IO) {
                val providerName = if (persona.model.startsWith("[") && persona.model.contains("] ")) {
                    persona.model.substring(1, persona.model.indexOf("]"))
                } else ""

                val apiSetting = (_cloudApis.value + _customCloudApis.value).find { it.name == providerName }
                    ?: throw Exception("No config for provider '$providerName'")

                if (apiSetting.apiKey.isBlank() && apiSetting.icon != "C") throw Exception("API Key for ${apiSetting.name} is missing")
                val request = buildCloudRequest(apiSetting, persona, text, currentTurnInHistory)
                getDynamicClient(persona).newCall(request).execute().use { response ->
                    val bodyStr = response.body.string()
                    if (!response.isSuccessful) {
                        val detail = try { JSONObject(bodyStr).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                        throw Exception("Error ${response.code}: ${detail ?: bodyStr.take(200)}")
                    }
                    val (responseText, reasonText, usage) = parseCloudResponse(apiSetting, persona, bodyStr)
                    withContext(Dispatchers.Main) {
                        val currentUsage = _sessionUsage.value
                        _sessionUsage.value = UsageInfo(currentUsage.promptTokens + usage.promptTokens, currentUsage.completionTokens + usage.completionTokens, currentUsage.totalTokens + usage.totalTokens, currentUsage.cost + usage.cost)
                        _totalCost.value += usage.cost
                        saveSettings()
                    }
                    responseText to reasonText
                }
            }
            val responseTimeMs = System.currentTimeMillis() - startTime
            if (useDeviceVoice) {
                _messages.value = _messages.value + ChatMessage("assistant", responseData.first, responseData.second, responseTimeMs = responseTimeMs)
                saveSettings()
                playResponse(persona, deviceText = responseData.first)
            } else if (persona.voiceMode == VoiceMode.GATEWAY) {
                val audioBytes = synthesizeWithGateway(responseData.first, persona)
                val audioPath = if (audioBytes != null) {
                    val outFile = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
                    outFile.writeBytes(audioBytes)
                    outFile.absolutePath
                } else null

                _messages.value = _messages.value + ChatMessage("assistant", responseData.first, responseData.second, audioFilePath = audioPath, responseTimeMs = responseTimeMs)
                saveSettings()

                if (audioPath != null) {
                    playResponse(persona, file = File(audioPath))
                }
            } else {
                _messages.value = _messages.value + ChatMessage("assistant", responseData.first, responseData.second, responseTimeMs = responseTimeMs)
                saveSettings()
            }
        } catch (e: Exception) {
            _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message}", isError = true)
        } finally {
            if (_state.value == AssistantState.THINKING) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

private suspend fun AssistantService.performDirectOllamaChat(baseUrl: String, model: String, text: String, persona: Persona, currentTurnInHistory: Boolean): Triple<String, String?, ByteArray?> {
    // Resolve backend URL: fallback if empty or mistakenly pointing to a gateway (8880)
    val stripped = baseUrl.trim()
    val resolvedBackend = if (stripped.isBlank() || stripped.contains(":8880")) {
        _ollamaBaseUrls.value.firstOrNull()?.url ?: ""
    } else {
        stripped.removeSuffix("/")
    }

    if (resolvedBackend.isBlank()) throw Exception("No Ollama server configured")

    val url = if (resolvedBackend.endsWith("/api/chat")) resolvedBackend else "${resolvedBackend.trimEnd('/')}/api/chat"
    val json = JSONObject()
    json.put("model", model)
    json.put("stream", false)

    val msgsArray = org.json.JSONArray()
    if (persona.isTranslator) {
        msgsArray.put(JSONObject().put("role", "system").put("content", "You are a translation engine. Translate the user's text into the requested target language. Return ONLY the translation, no explanations, no extra commentary."))
        msgsArray.put(JSONObject().put("role", "user").put("content", "Translate the following text to ${persona.targetLanguage}: $text"))
    } else {
        // Context injection
        val currentDateTime = getCurrentDateTimeString()
        var finalSystemPrompt = "Current date and time: $currentDateTime\n\n${persona.systemPrompt}"

        val searchContext = if (isNewsRequest(text)) {
            fetchNewsContext()
        } else if (persona.webSearchEnabled) {
            fetchWebSearchContext(text)
        } else ""

        if (searchContext.isNotEmpty()) {
            finalSystemPrompt = "$searchContext\n\n$finalSystemPrompt"
        }

        // Add system prompt first
        android.util.Log.d("AssistantService", "DEBUG SYSTEM PROMPT: $finalSystemPrompt")
        msgsArray.put(JSONObject().put("role", "system").put("content", finalSystemPrompt))

        // Budget-aware history selection
        val contextWindow = 8192
        val reservedOutput = persona.maxTokens.coerceAtLeast(1024)
        val budget = maxOf(512, contextWindow - reservedOutput - estimateTokens(finalSystemPrompt) - estimateTokens(text))

        // A1: positional slice — the caller says whether the current user turn is
        // already the last entry in _messages (text flow appends it before the
        // request; the voice flow does not). No text-content matching.
        val allNonError = _messages.value.filter { !it.isError }
        val historySource = if (currentTurnInHistory && allNonError.isNotEmpty()) allNonError.dropLast(1) else allNonError

        var usedTokens = 0
        val history = mutableListOf<ChatMessage>()
        for (i in historySource.indices.reversed()) {
            val msg = historySource[i]
            val tokens = estimateTokens(msg.text)
            if (usedTokens + tokens > budget) break
            history.add(0, msg)
            usedTokens += tokens
        }

        android.util.Log.d("AssistantService", "Ollama Budget: history=${history.size}, used=$usedTokens, budget=$budget")

        history.forEach { msg ->
            msgsArray.put(JSONObject().put("role", msg.role).put("content", msg.text))
        }

        // A1: the current user turn is ALWAYS appended — repeated inputs must reach
        // the model as new turns even when identical text exists earlier in history.
        msgsArray.put(JSONObject().put("role", "user").put("content", text))
    }

    json.put("messages", msgsArray)

    val options = JSONObject()
    options.put("temperature", persona.temperature)
    options.put("top_p", persona.topP)
    options.put("top_k", persona.topK)
    options.put("repeat_penalty", persona.repeatPenalty)
    options.put("num_ctx", 8192)
    android.util.Log.d("AssistantService", "DEBUG: persona.name=${persona.name} enableThinking=${persona.enableThinking} isKnownThinking=${isKnownThinkingModel(model)}")
    options.put("think", persona.enableThinking && isKnownThinkingModel(model))
    options.put("num_predict", persona.maxTokens)

    json.put("options", options)

    val requestBuilder = Request.Builder()
        .url(url)
        .post(json.toString().toRequestBody("application/json".toMediaType()))

    _ollamaBaseUrls.value.find { it.url == resolvedBackend }?.let { config ->
        if (!config.username.isNullOrBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(config.username, config.password ?: ""))
        }
    }

    getDynamicClient(persona).newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) throw Exception("Ollama error: ${response.code}")
        val body = response.body.string()
        val message = JSONObject(body).getJSONObject("message")
        val rText = message.getString("content")
        val reasoning = if (persona.enableThinking && message.has("thinking")) message.getString("thinking").ifBlank { null } else null

        return Triple(rText, reasoning, null)
    }
}

private suspend fun AssistantService.buildCloudRequest(api: CloudApiSetting, persona: Persona, text: String, currentTurnInHistory: Boolean): Request {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val json = JSONObject()
    
    val currentDateTime = getCurrentDateTimeString()
    var finalSystemPrompt = "Current date and time: $currentDateTime\n\n${persona.systemPrompt}"

    val searchContext = if (isNewsRequest(text)) {
        fetchNewsContext()
    } else if (persona.webSearchEnabled) {
        fetchWebSearchContext(text)
    } else ""

    if (searchContext.isNotEmpty()) {
        finalSystemPrompt = "$searchContext\n\n$finalSystemPrompt"
    }

    // Budget-aware history selection
    val contextWindow = 128000 
    val reservedOutput = persona.maxTokens.coerceAtLeast(1024)
    val budget = maxOf(512, contextWindow - reservedOutput - estimateTokens(finalSystemPrompt) - estimateTokens(text))
    
    // A1: positional slice — the caller says whether the current user turn is already
    // the last entry in _messages (text flow appends it; voice flow does not). No
    // text-content matching is used to decide what to drop.
    val allNonError = _messages.value.filter { !it.isError }
    val historySource = if (currentTurnInHistory && allNonError.isNotEmpty()) allNonError.dropLast(1) else allNonError

    var usedTokens = 0
    val history = mutableListOf<ChatMessage>()
    for (i in historySource.indices.reversed()) {
        val msg = historySource[i]
        val tokens = estimateTokens(msg.text)
        if (usedTokens + tokens > budget) break
        history.add(0, msg)
        usedTokens += tokens
    }
    
    android.util.Log.d("AssistantService", "Cloud Budget: history=${history.size}, used=$usedTokens, budget=$budget")

    val actualModel = persona.model.substringAfter("] ")
    val baseUrl = api.baseUrl.trim().removeSuffix("/")

    return when (api.icon) {
        "A" -> {
            json.put("model", actualModel).put("max_tokens", persona.maxTokens).put("system", finalSystemPrompt)
            val msgArray = org.json.JSONArray()
            // Anthropic requires the conversation to start with 'user' and strictly
            // alternate roles. A1: same-role turns are MERGED (never silently
            // skipped), and the current user turn is always appended below.
            var lastRole = ""
            fun appendAnthropicTurn(role: String, turnText: String) {
                if (turnText.isBlank()) return
                if (role == lastRole && msgArray.length() > 0) {
                    val prev = msgArray.getJSONObject(msgArray.length() - 1)
                    prev.put("content", prev.getString("content") + "\n\n" + turnText)
                } else {
                    msgArray.put(JSONObject().put("role", role).put("content", turnText))
                    lastRole = role
                }
            }
            history.dropWhile { it.role != "user" }.forEach { msg -> appendAnthropicTurn(msg.role, msg.text) }
            // A1: the current user turn always reaches the model.
            appendAnthropicTurn("user", text)
            json.put("messages", msgArray)
            Request.Builder().url("$baseUrl/v1/messages").header("x-api-key", api.apiKey).header("anthropic-version", "2023-06-01").header("content-type", "application/json").post(json.toString().toRequestBody(mediaType)).build()
        }
        "G" -> {
            val contents = org.json.JSONArray()
            // Gemini requires user/model alternation too. A1: same-role turns are
            // merged as extra parts (never skipped), and the current user turn is
            // always appended below.
            fun appendGeminiTurn(role: String, turnText: String) {
                if (turnText.isBlank()) return
                val geminiRole = if (role == "assistant") "model" else "user"
                if (contents.length() > 0) {
                    val last = contents.getJSONObject(contents.length() - 1)
                    if (last.getString("role") == geminiRole) {
                        last.getJSONArray("parts").put(JSONObject().put("text", turnText))
                        return
                    }
                }
                contents.put(JSONObject().put("role", geminiRole).put("parts", org.json.JSONArray().put(JSONObject().put("text", turnText))))
            }
            history.forEach { appendGeminiTurn(it.role, it.text) }
            // A1: the current user turn always reaches the model.
            appendGeminiTurn("user", text)
            json.put("contents", contents)

            val genConfig = JSONObject()
            genConfig.put("maxOutputTokens", persona.maxTokens)
            json.put("generationConfig", genConfig)

            // Add system instruction for Gemini if needed
            val sysInst = JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", finalSystemPrompt)))
            json.put("system_instruction", sysInst)

            Request.Builder().url("$baseUrl/v1beta/models/$actualModel:generateContent?key=${api.apiKey}").post(json.toString().toRequestBody(mediaType)).build()
        }
        else -> {
            json.put("model", actualModel).put("max_tokens", persona.maxTokens)
            val msgs = org.json.JSONArray().put(JSONObject().put("role", "system").put("content", finalSystemPrompt))
            history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
            // A1: the current user turn is ALWAYS appended — no text-matching dedup.
            msgs.put(JSONObject().put("role", "user").put("content", text))
            json.put("messages", msgs)
            val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
            Request.Builder().url(url).header("Authorization", "Bearer ${api.apiKey}").post(json.toString().toRequestBody(mediaType)).build()
        }
    }
}

private fun AssistantService.parseCloudResponse(api: CloudApiSetting, persona: Persona, body: String): Triple<String, String?, UsageInfo> {
    android.util.Log.d("UsageTracking", "Parsing response from ${api.name}: ${body.take(500)}")
    val json = JSONObject(body)
    var text = ""; var reasoning: String? = null; var pTokens = 0; var cTokens = 0
    when (api.icon) {
        "A" -> {
            text = json.getJSONArray("content").getJSONObject(0).getString("text")
            if (json.has("usage")) {
                val usage = json.getJSONObject("usage")
                pTokens = usage.optInt("input_tokens", 0)
                cTokens = usage.optInt("output_tokens", 0)
            }
        }
        "G" -> {
            text = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            if (json.has("usageMetadata")) {
                val usage = json.getJSONObject("usageMetadata")
                pTokens = usage.optInt("promptTokenCount", 0)
                cTokens = usage.optInt("candidatesTokenCount", 0)
            }
        }
        else -> {
            val choices = json.getJSONArray("choices")
            val message = choices.getJSONObject(0).getJSONObject("message")
            text = message.getString("content")
            reasoning = if (persona.enableThinking && message.has("reasoning_content")) message.getString("reasoning_content") else null
            if (json.has("usage")) {
                val usage = json.getJSONObject("usage")
                pTokens = usage.optInt("prompt_tokens", 0)
                cTokens = usage.optInt("completion_tokens", 0)
            }
        }
    }
    val cost = calculateCost(persona, pTokens, cTokens)
    android.util.Log.d("UsageTracking", "Extracted Tokens: p=$pTokens, c=$cTokens, cost=$cost")
    return Triple(text, reasoning, UsageInfo(pTokens, cTokens, pTokens + cTokens, cost))
}

private fun AssistantService.isKnownThinkingModel(modelName: String): Boolean {
    val name = modelName.lowercase()
    return name.contains("deepseek-r1") || name.contains("deepseek-reasoner") || name.contains("-r1")
}

private fun AssistantService.calculateCost(persona: Persona, promptTokens: Int, completionTokens: Int): Double {
    val model = persona.model.lowercase()
    val pricingMap = settingsManager.getModelPricing()
    
    val actualModelId = persona.model.substringAfter("] ").lowercase().trim()
    val provider = if (persona.model.startsWith("[")) persona.model.substring(1, persona.model.indexOf("]")).lowercase().trim() else ""
    
    val ignored = setOf("latest", "chat", "v1", "v2", "v3", "online")
    val modelParts = actualModelId.split("-", ".", "_").filter { it.isNotBlank() && it !in ignored }
    val vendorParts = provider.split("-", ".", "_").filter { it.isNotBlank() && it !in ignored }
    val allParts = (modelParts + vendorParts).map { it.replace("-", "").replace("_", "").replace(".", "") }

    // Attempt best-effort match with OpenRouter pricing using keyword scoring
    val matchedEntry = if (allParts.isEmpty()) null else {
        pricingMap.entries
            .map { entry ->
                val idL = entry.key.lowercase()
                val idNormalized = idL.replace("-", "").replace("_", "").replace(".", "").replace("/", "")
                val score = allParts.count { idNormalized.contains(it) }
                entry to score
            }
            .filter { it.second >= minOf(2, allParts.size) }
            .maxByOrNull { it.second }?.first
    }

    if (matchedEntry != null) {
        val pricing = matchedEntry.value
        val cost = (promptTokens * pricing.prompt) + (completionTokens * pricing.completion)
        android.util.Log.d("UsageTracking", "Cost calc using dynamic rate for $actualModelId ($matchedEntry): $cost")
        return cost
    }

    // Fallback to hardcoded values (unit: USD per 1M tokens)
    val (iP, oP) = when {
        model.contains("claude-3-5-sonnet") -> 3.0 to 15.0
        model.contains("claude-3-5-haiku") -> 0.25 to 1.25
        model.contains("gpt-4o-mini") -> 0.15 to 0.60
        model.contains("gpt-4o") -> 5.0 to 15.0
        model.contains("deepseek") -> 0.27 to 1.10
        model.contains("gemini-1.5-flash") -> 0.075 to 0.30
        model.contains("gemini-1.5-pro") -> 3.5 to 10.5
        model.contains("llama-3.1-8b") -> 0.05 to 0.05
        model.contains("llama-3.1-70b") -> 0.60 to 0.60
        else -> 0.01 to 0.01 // Minimal fallback for unknown cloud models
    }
    val cost = (promptTokens * iP / 1_000_000.0) + (completionTokens * oP / 1_000_000.0)
    android.util.Log.d("UsageTracking", "Cost calc for $model (fallback): p=$promptTokens ($iP), c=$completionTokens ($oP) -> total=$cost")
    return cost
}

fun AssistantService.syncOpenRouterPricing(force: Boolean = false) {
    if (!force && _lastPriceSyncTimestamp.value != 0L && System.currentTimeMillis() - _lastPriceSyncTimestamp.value < 24 * 60 * 60 * 1000) {
        return
    }

    serviceScope.launch(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
            publicClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@launch
                val body = response.body.string()
                val json = JSONObject(body)
                val data = json.getJSONArray("data")
                val newPricing = mutableMapOf<String, ModelPricing>()
                
                for (i in 0 until data.length()) {
                    val entry = data.getJSONObject(i)
                    val id = entry.getString("id")
                    val pricing = entry.optJSONObject("pricing") ?: continue
                    val prompt = pricing.optString("prompt", "0").toDoubleOrNull() ?: 0.0
                    val completion = pricing.optString("completion", "0").toDoubleOrNull() ?: 0.0
                    newPricing[id] = ModelPricing(prompt, completion)
                }
                
                settingsManager.saveModelPricing(newPricing)
                val now = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    _lastPriceSyncTimestamp.value = now
                    settingsManager.saveLastPriceSyncTimestamp(now)
                }
                
                android.util.Log.d("PricingSync", "Synced ${newPricing.size} models from OpenRouter")
                
                android.util.Log.d("PricingSync", "Synced ${newPricing.size} models from OpenRouter")
            }
        } catch (e: Exception) {
            android.util.Log.e("PricingSync", "Sync failed", e)
        }
    }
}

private fun Response.decodeTextHeader(name: String, fallback: String): String {
    val encoded = this.header(name) ?: return fallback
    return try { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { fallback }
}

private fun AssistantService.estimateTokens(text: String): Int = (text.length / 4.0).toInt().coerceAtLeast(1)
