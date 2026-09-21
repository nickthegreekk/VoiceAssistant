package com.nikosm.voiceassistant

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ---- Plain-text attachment support ----
// Files are read in-app, validated as plain text, and their content is injected into
// the prompt text sent to the model — no multipart, no server-side extraction. This
// works uniformly across Cloud, Direct-Ollama, and Gateway backends.
private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024 // 25 MB
private const val ATTACHMENT_MB = MAX_ATTACHMENT_BYTES / (1024 * 1024)

private class AttachmentException(message: String) : Exception(message)
private class SafetyBlockedException(message: String) : Exception(message)

private val plaintextMimeTypes = setOf(
    "text/plain", "text/markdown", "text/x-markdown",
    "application/json", "text/x-json",
    "application/yaml", "text/yaml", "text/x-yaml",
    "application/xml", "text/xml",
    "text/html", "application/html",
    "text/css",
    "text/x-shellscript", "text/x-sh",
    "text/x-python", "text/x-python-script",
    "text/x-c", "text/x-csrc", "text/x-chdr", "text/x-c++", "text/x-c++src", "text/x-c++hdr",
    "text/x-java-source",
    "text/x-javascript", "application/javascript",
    "text/x-ruby",
    "text/x-sql",
    "text/x-csharp",
    "text/x-go-source",
    "text/x-rust",
    "text/x-php"
)

private val plaintextExtensions = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "xml", "html", "htm", "css",
    "js", "py", "c", "h", "hpp", "cpp", "java", "kt", "rb", "sql", "cs", "go",
    "rs", "php", "sh", "bash", "zsh", "log", "conf", "ini", "cfg", "toml", "env",
    "properties", "csv", "tsv", "ts", "tsx", "jsx", "dockerfile", "makefile", "gitignore"
)

// L4: the extension-vs-basename decision behind isPlainTextAttachment, split out (and
// internal) because it is the part that was wrong and it needs no Android surface — a Uri
// cannot be built in a JVM unit test, a file name can. PlainTextAttachmentNameTest pins
// this directly.
internal fun isPlainTextFileName(fileName: String): Boolean {
    val name = fileName.lowercase()
    // L4: a name WITH a dot is matched by its extension (`notes.md` -> "md"); a name
    // WITHOUT one is matched as a whole basename (`Dockerfile`, `Makefile`, and the
    // dot-prefixed `.gitignore` via its "gitignore" entry). plaintextExtensions holds
    // those extension-less entries literally, but the old lookup required a literal dot
    // in the filename, so `substringAfterLast(".", "")` returned "" for "Dockerfile"
    // and a real Dockerfile/Makefile was rejected as an unsupported attachment.
    val dot = name.lastIndexOf('.')
    val candidate = if (dot >= 0) name.substring(dot + 1) else name
    return candidate in plaintextExtensions
}

internal fun AssistantService.isPlainTextAttachment(uri: Uri): Boolean {
    val mime = contentResolver.getType(uri)
    if (mime != null && mime in plaintextMimeTypes) return true
    val name = uri.path?.substringAfterLast('/') ?: return false
    return isPlainTextFileName(name)
}

// Reads an attachment as strict UTF-8 text, capped at MAX_ATTACHMENT_BYTES. Fails
// cleanly (with a clear message) for unsupported types, oversized files, or non-UTF-8
// content, rather than sending garbage bytes to the model.
internal suspend fun AssistantService.readAttachmentText(uri: Uri): String = withContext(Dispatchers.IO) {
    if (!isPlainTextAttachment(uri)) {
        throw AttachmentException(
            "'$uri' is not a supported plain-text file. Only text files (.txt, .md, .json, .yaml, code, etc.) can be attached."
        )
    }
    contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_ATTACHMENT_BYTES) {
                throw AttachmentException("Attachment exceeds the $ATTACHMENT_MB MB size limit.")
            }
            out.write(chunk, 0, read)
        }
        val bytes = out.toByteArray()
        if (!isValidUtf8(bytes)) {
            throw AttachmentException("Attachment '$uri' is not valid UTF-8 text.")
        }
        return@withContext String(bytes, Charsets.UTF_8)
    }
    throw AttachmentException("Could not open attachment: $uri")
}

// Validates that `bytes` is well-formed UTF-8. Kotlin's lossy decoder silently
// substitutes invalid bytes; a round-trip re-encode detects malformed input, since
// only valid UTF-8 encodes back to identical bytes.
private fun isValidUtf8(bytes: ByteArray): Boolean {
    val decoded = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { return false }
    val reEncoded = decoded.toByteArray(Charsets.UTF_8)
    if (reEncoded.size != bytes.size) return false
    for (i in 0 until bytes.size) {
        if (reEncoded[i] != bytes[i]) return false
    }
    return true
}

// Builds the "--- Attached file: X ---" sections for every attachment, throwing
// AttachmentException on the first unreadable / unsupported / oversized file.
private suspend fun AssistantService.buildAttachmentSections(attachments: List<Uri>): String {
    return withContext(Dispatchers.IO) {
        buildString {
            attachments.forEachIndexed { index, uri ->
                val name = uri.lastPathSegment ?: "file_$index"
                val content = readAttachmentText(uri)
                append("--- Attached file: $name ---\n")
                append(content)
                append("\n--- End of $name ---\n\n")
            }
        }
    }
}

// Returns the prompt text fed to the model: the user question plus injected attachment
// content. This is the single mechanism that delivers extracted file text to all
// backends. Empty attachments just pass the original text through.
private suspend fun AssistantService.buildModelPrompt(text: String, attachments: List<Uri>): String {
    if (attachments.isEmpty()) return text
    return "$text\n\n${buildAttachmentSections(attachments)}"
}

// A5: a user-initiated stop (Stop button -> currentCall.cancel()) surfaces as an
// IOException in every flow: the gateway loops detect call.isCanceled() and rethrow
// IOException("Cancelled"), while the cloud and direct-Ollama calls let OkHttp's own
// IOException("Canceled") propagate directly. Recognising the shapes lets us skip the
// spurious "Error: Cancelled" bubble and just return to idle.
private fun isUserCancellation(e: Throwable): Boolean =
    e is java.io.IOException && (e.message == "Cancelled" || e.message == "Canceled")

// A3: a failed server becomes eligible for retry after a cooldown (e.g. 60 s) instead
// of being excluded until a manual refresh. Server URL -> epoch ms of when the next
// attempt is allowed. Held in a plain (non-reactive) field because the retry pool only
// needs the most recent mark; UI display/logic are unaffected. M4: ConcurrentHashMap —
// it is READ on Dispatchers.IO (isServerHealthyForRetry, from the failover pools built
// inside withContext(IO) blocks) and WRITTEN on Main (the failure handlers), so the
// map type itself provides the cross-thread safety a HashMap would lack. Values are
// never null (epoch-ms stamps) and keys are non-null URLs, so ConcurrentHashMap's
// null restrictions don't apply.
internal val serverFailCooldownUntilMillis = ConcurrentHashMap<String, Long>()

// A3: returns true if the server is currently OK to try. A server is allowed back once
// FAILED_COOLDOWN_MS has elapsed since it was marked failed, and a status that isn't a
// "failed" exclusion (null, "Online", or a non-failed string) is always tryable.
internal fun AssistantService.isServerHealthyForRetry(url: String): Boolean {
    if (_serverStatus.value[url]?.lowercase()?.contains("failed") != true) return true
    return (serverFailCooldownUntilMillis[url] ?: 0L) < System.currentTimeMillis()
}

internal const val FAILED_COOLDOWN_MS = 60_000L // 60 seconds

internal fun AssistantService.sendAudioToServer(file: File, currentPersona: Persona) {
    if (currentPersona.model.isBlank()) {
        _messages.value = _messages.value + ChatMessage("assistant", "Please choose a model for this persona in its settings.", isError = true)
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    serviceScope.launch {
        val startTime = System.currentTimeMillis()
        // A2/B1: capture the request identity (generation + persona). The response is
        // applied only if both are still current when it arrives.
        val generation = nextChatRequestSeq()
        val personaName = currentPersona.name
        // M2: record this turn as the owner of the THINKING state it is about to set.
        // The ownership pair (owner generation + `_state == THINKING`) is what
        // stopVadListening() consults, so toggling hands-free off cannot force IDLE
        // underneath a live turn — and thereby cannot make that turn's own
        // `_state == THINKING` finally guard fail, which used to leak the audio focus
        // the turn retained through THINKING.
        recordThinkingOwner(generation)
        _state.value = AssistantState.THINKING
        updateNotification("Thinking...")

        val useDeviceVoice = currentPersona.voiceMode != VoiceMode.GATEWAY

        if (currentPersona.isCloud && isCloudModel(currentPersona.model)) {
            serviceScope.launch {
                try {
                    val transcribedText = transcribeWithGateway(file, currentPersona)
                        ?: throw Exception("Could not transcribe audio. Check Gateway connection.")
                    if (transcribedText.isBlank()) {
                        // M3: the gateway responded fine — the audio just had no
                        // detectable speech. Caller-level concern: surface the
                        // empty-turn error without penalizing the server's
                        // health/cooldown status.
                        throw Exception("Empty transcription result")
                    }
                    // H1: the user's spoken turn must reach the transcript before the
                    // response is requested, exactly as the gateway-voice (apply-time
                    // append) and text-flow (append-before-send) call sites do.
                    // performCloudChat only ever appends the ASSISTANT message, so a
                    // cloud voice turn used to leave the question out of _messages and
                    // out of the persisted history. That is not just a display gap:
                    // buildCloudRequest assembles the next request FROM _messages, and
                    // an Anthropic persona runs `history.dropWhile { it.role != "user" }`
                    // over it — with an assistant-only history that dropped EVERYTHING,
                    // so a cloud voice conversation had no memory between turns (and
                    // Gemini/OpenAI personas lost the user side of the prior turn).
                    // Same ownership gates as the failure path below, so a superseded or
                    // persona-switched turn can never leave an orphaned user message.
                    if (!isChatRequestCurrent(generation)) {
                        // Stop / a newer request owns the transcript and the focus —
                        // append nothing and skip the (now pointless, paid) model call.
                        Log.d(
                            "AssistantService",
                            "Voice transcript discarded — superseded before it could be applied"
                        )
                        return@launch
                    }
                    if (!isChatContextCurrent(generation, personaName)) {
                        // The active persona changed: never write this turn into another
                        // persona's transcript. performCloudChat never runs, so its
                        // finally won't release the focus this turn retained — do it here.
                        android.util.Log.d(
                            "AssistantService",
                            "Voice transcript discarded — active persona changed"
                        )
                        abandonAssistantFocus()
                        _state.value = AssistantState.IDLE
                        updateNotification("Ready to help")
                        return@launch
                    }
                    _messages.value = _messages.value + ChatMessage("user", transcribedText)
                    saveSettings()
                    // currentTurnInHistory = true: the user turn is now the last entry in
                    // _messages, so buildCloudRequest drops exactly that entry from the
                    // history slice and re-appends it as the current turn — it reaches
                    // the model once, and the prior turns stay intact.
                    performCloudChat(transcribedText, currentPersona, useDeviceVoice, startTime, currentTurnInHistory = true, generation = generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isChatRequestCurrent(generation)) {
                        android.util.Log.d("AssistantService", "Voice chat request superseded by a newer request — discarding failure: ${e.message}")
                    } else if (isUserCancellation(e)) {
                        // A5: the user deliberately stopped this request — no error bubble.
                        android.util.Log.d("AssistantService", "Voice chat request cancelled by user — no error bubble")
                        // Transcription failed before performCloudChat existed, so its
                        // finally never ran — release the focus stopRecording() retained
                        // through THINKING (prompt un-duck, no indefinite ducking).
                        abandonAssistantFocus()
                        _state.value = AssistantState.IDLE
                        updateNotification("Ready to help")
                    } else {
                        android.util.Log.e("AssistantService", "Cloud voice chat failed", e)
                        if (isChatContextCurrent(generation, personaName)) {
                            _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message}", isError = true)
                        } else {
                            android.util.Log.d("AssistantService", "Persona changed during request — discarding failure message: ${e.message}")
                        }
                        // Same as above: pre-performCloudChat failure, no finally ran.
                        abandonAssistantFocus()
                        _state.value = AssistantState.IDLE
                        updateNotification("Ready to help")
                    }
                }
            }
            return@launch
        }

        // Hoisted so the Stage-1 streaming cancellation catch can persist the
        // transcribed text alongside the kept partial response (D2).
        var transcribedText: String = ""
        // Focus-retention guard: stopRecording() now retains the recorder's
        // focus through THINKING when the turn will speak. This flag marks
        // whether playback was actually handed off — if the turn terminates
        // without playing (server error, empty transcription, user cancel,
        // persona switch), the finally below must release the retained focus
        // (the original indefinite-duck bug). NOT set for superseded turns:
        // a newer request then owns the flow and its focus lifecycle.
        var playbackRequested = false
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
                         transcribedText = transcribeWithGateway(file, currentPersona)
                             ?: throw Exception("Could not transcribe audio. Check Gateway connection.")
                         if (transcribedText.isBlank()) {
                             // M3: gateway healthy, audio had no detectable speech —
                             // caller-level error, no server health penalty.
                             throw Exception("Empty transcription result")
                         }

                         // 2. Chat with Ollama
                         val directRes = performDirectOllamaChat(ollamaBase, actualModel, transcribedText, currentPersona, currentTurnInHistory = false, generation = generation)
                         // Fix #5 (voice-flow follow-up): clean markdown for TTS on IO —
                         // the cleaned variant feeds the on-device engines (5th element);
                         // the chat bubble/history keep the original markdown. This branch
                         // was missed when the consumer gained its 5th element, crashing
                         // every voice turn to a direct-Ollama persona on the destructuring.
                         val cleanedForTts = cleanTextForTts(directRes.first)

                         // If it's a gateway voice mode, we need to fetch audio separately
                         if (currentPersona.voiceMode == VoiceMode.GATEWAY) {
                             val (audioBytes, wordTsJson) = synthesizeWithGateway(directRes.first, currentPersona)
                             return@withContext listOf(transcribedText, directRes.first, directRes.second, audioBytes, cleanedForTts)
                         }

                         return@withContext listOf(transcribedText, directRes.first, directRes.second, directRes.third, cleanedForTts)
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
                    addFormDataPart("num_ctx", currentPersona.numCtx.toString())
                    addFormDataPart("context_time", currentDateTime)
                    if (currentPersona.isTranslator) {
                        addFormDataPart("target_language", currentPersona.targetLanguage)
                    }
                }.build()

                val allGateways = _serverBases.value

                // S2: data (audio/transcripts) is only sent to the persona's configured
                // backend unless the persona explicitly opts into gateway failover.
                // M1: routed through the shared resolver, which keeps this path's
                // match-by-name (the `[Name] model` tag) AND adds trailing-slash/trim
                // normalization — the same resolution the gateway flows now use.
                val preferredGateway = if (currentPersona.backendUrl.isNotBlank()) {
                    resolveGatewayConfig(allGateways, currentPersona.backendUrl, displayServer)
                } else null

                val gwsToTry: List<ServerConfig> = if (currentPersona.allowGatewayFailover) {
                    // A3: only gateways currently healthy (or past a failure cooldown) are
                    // eligible for failover.
                    val workingGateways = allGateways.filter { isServerHealthyForRetry(it.url) }
                    buildList {
                        preferredGateway?.let { add(it) }
                        addAll(workingGateways.filter { it != preferredGateway })
                    }
                } else {
                    when {
                        preferredGateway != null -> listOf(preferredGateway)
                        currentPersona.backendUrl.isBlank() -> throw Exception(
                            "No gateway is configured for persona '${currentPersona.name}'. Set a Backend URL in its persona settings, or enable 'Allow Gateway Failover'."
                        )
                        else -> throw Exception(
                            "Gateway '${currentPersona.backendUrl}' for persona '${currentPersona.name}' is not in the configured gateway list. Check Settings, or enable 'Allow Gateway Failover'."
                        )
                    }
                }

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

                    when (gw.effectiveAuthType) {
                        AuthType.NONE -> { /* no Authorization header */ }
                        AuthType.BASIC -> if (!gw.username.isNullOrBlank()) {
                            requestBuilder.header("Authorization", Credentials.basic(gw.username, gw.password ?: ""))
                        }
                        AuthType.API_KEY -> if (!gw.apiKey.isNullOrBlank()) {
                            requestBuilder.header("Authorization", "Bearer ${gw.apiKey}")
                        }
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
                                _serverBases.value.find { it.url == base }?.let { statusMap[it.url] = "Online" }
                                _serverStatus.value = statusMap
                            }

                            if (contentType.contains("application/json")) {
                                val body = response.body.string()
                                val json = JSONObject(body)
                                val rText = json.optString("translated_text", "")
                                val note = json.optString("note", "")
                                val finalRText = if (note.isNotEmpty()) "$rText\n\n($note)" else rText
                                val displayText = finalRText.ifBlank { "..." }
                                // Fix #5: clean markdown for TTS here, on IO — the cleaned
                                // variant feeds the on-device engines (5th element); the
                                // chat bubble/history keep the original markdown.
                                val cleanedForTts = cleanTextForTts(displayText)
                                return@withContext listOf(uText, displayText, null, null, cleanedForTts)
                            } else {
                                val rText = response.decodeTextHeader("X-Translated-Text-B64",
                                    response.decodeTextHeader("X-Response-Text-B64", "..."))
                                val bytes = response.body.bytes()
                                // Fix #5: clean markdown for TTS on IO (see the JSON branch).
                                val cleanedForTts = cleanTextForTts(rText)
                                return@withContext listOf(uText, rText, null, if (useDeviceVoice) null else bytes, cleanedForTts)
                            }
                        }
                    } catch (e: Exception) {
                        if (call.isCanceled()) throw java.io.IOException("Cancelled")

                        // A3: mark failed (with cooldown). A 4xx means the server responded,
                        // so it's reachable — worth keeping its message visible, but the
                        // cooldown is the thing that actually gates re-entry to the pool.
                        val failureLabel = if (e is okhttp3.internal.http2.StreamResetException || (e.message?.contains("HTTP ") == true || e.message?.contains("Server error:") == true)) {
                            e.message?.take(30)
                        } else if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                            "connection/timeout"
                        } else {
                            e.message?.take(30)
                        }
                        withContext(Dispatchers.Main) {
                            val statusMap = _serverStatus.value.toMutableMap()
                            _serverBases.value.find { it.url == base }?.let { cfg ->
                                statusMap[cfg.url] = "failed: $failureLabel"
                                serverFailCooldownUntilMillis[cfg.url] = System.currentTimeMillis() + FAILED_COOLDOWN_MS
                            }
                            _serverStatus.value = statusMap
                        }

                        lastEx = e
                    }
                }
                throw lastEx ?: Exception("All connection attempts failed")
            }
            // A2/B1: apply the response only if the context is unchanged.
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Chat response discarded — superseded by a newer request (gen $generation)")
                return@launch
            }
            // A6: this flow's network call has finished. Clear the in-flight reference
            // before applying (or discarding for a persona change) so currentCall never
            // points at a completed call. Safe here because no newer request exists,
            // so currentCall cannot belong to another flow.
            currentCall = null
            if (!isChatContextCurrent(generation, personaName)) {
                android.util.Log.d("AssistantService", "Chat response discarded — active persona changed (gen $generation)")
                return@launch
            }
            val (uText, rText, reasoning, bytes, cleanedForTts) = responseData as List<Any?>
            val responseTimeMs = System.currentTimeMillis() - startTime
            val audioPath = if (bytes != null && !useDeviceVoice) {
                val outFile = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(bytes as ByteArray)
                outFile.absolutePath
            } else null

            // Stage-1 streaming: when this turn streamed, the trailing assistant
            // placeholder is already in the list (content built up during the
            // stream) but the user turn is NOT (voice flow appends user+assistant
            // together at apply). Swap: drop the placeholder, append user+final.
            // H3: consume only OUR OWN placeholder (null ⇒ a newer turn owns it, or this
            // one was superseded), and never clear a newer turn's published streaming text.
            val streamedIdx = takeStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            if (streamedIdx != null && streamedIdx < _messages.value.size) {
                val base = _messages.value.toMutableList().also { it.removeAt(streamedIdx) }.toList()
                _messages.value = base +
                    ChatMessage("user", uText as String) +
                    ChatMessage("assistant", rText as String, reasoning as? String, audioFilePath = audioPath, responseTimeMs = responseTimeMs)
            } else {
                _messages.value = _messages.value +
                    ChatMessage("user", uText as String) +
                    ChatMessage("assistant", rText as String, reasoning as? String, audioFilePath = audioPath, responseTimeMs = responseTimeMs)
            }
            saveSettings()

            if (useDeviceVoice) {
                playbackRequested = true
                playResponse(currentPersona, deviceText = cleanedForTts as String)
            } else if (audioPath != null) {
                playbackRequested = true
                playResponse(currentPersona, file = File(audioPath))
            } else if (currentPersona.voiceMode == VoiceMode.GATEWAY) {
                // Stage-2 streaming TTS: gateway mode with deferred synthesis.
                // The full text was NOT pre-synthesized; use the chunked
                // sequential pipeline (synthesize chunk 0, play, synthesize
                // chunk 1 while playing, etc.) for dramatically lower
                // first-audio latency on long responses.
                playbackRequested = true
                playChunkedTtsGateway(rText as String, currentPersona, ttsGeneration)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamCancelledByUserException) {
            // D2: Stop interrupted a mid-stream voice turn — keep the partial as
            // genuine history: user turn + partial assistant message (the voice
            // flow's apply would have appended both).
            // H3: ownership FIRST. This block used to be guarded only by the persona
            // name, so a superseded turn's stop-unwind could append its stale partial
            // into the newer turn's transcript AND unconditionally clear the newer
            // turn's placeholder index + published streaming text — which left the
            // newer turn's own bubble to be appended AGAIN at apply (the duplicated
            // bubble pair). A turn that no longer owns the request touches nothing.
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Voice stream cancelled — superseded by a newer request; partial discarded")
            } else if (currentPersonaName == personaName && e.partialContent.isNotBlank()) {
                val streamedIdx = takeStreamedPlaceholderIfOwned(generation)
                val base = if (streamedIdx != null && streamedIdx < _messages.value.size) {
                    _messages.value.toMutableList().also { it.removeAt(streamedIdx) }.toList()
                } else _messages.value
                _messages.value = base +
                    ChatMessage("user", transcribedText) +
                    ChatMessage("assistant", e.partialContent, e.partialThinking.ifBlank { null })
                saveSettings()
            } else {
                removeBlankPlaceholderSvc(generation)
            }
            releaseStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            android.util.Log.d("AssistantService", "Voice stream cancelled by user — partial kept (${e.partialContent.length} chars)")
            currentCall = null
        } catch (e: StreamPersonaChangedException) {
            // Partial already written back into the previous persona's persisted
            // history inside performDirectOllamaChat — nothing to apply here.
            // H3: still only release OUR OWN placeholder / published streaming text.
            releaseStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            android.util.Log.d("AssistantService", "Voice stream cancelled — persona changed; partial kept in previous persona history")
            currentCall = null
        } catch (e: Exception) {
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Chat request superseded by a newer request — discarding failure: ${e.message}")
            } else if (isUserCancellation(e)) {
                // A5: the user deliberately stopped this request — no error bubble.
                android.util.Log.d("AssistantService", "Chat request cancelled by user — no error bubble")
                currentCall = null
            } else {
                android.util.Log.e("AssistantService", "Chat failed", e)
                if (isChatContextCurrent(generation, personaName)) {
                    _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message ?: "Unknown error"}", isError = true)
                } else {
                    android.util.Log.d("AssistantService", "Persona changed during request — discarding failure message: ${e.message}")
                }
                currentCall = null
            }
        } finally {
            // H3: every one of these is ownership-gated — a stale turn unwinding here
            // must not delete a newer turn's in-progress placeholder or blank its
            // published streaming text (see the helper block in AssistantService.kt).
            removeBlankPlaceholderSvc(generation)
            releaseStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            if (_state.value == AssistantState.THINKING && isChatRequestCurrent(generation)) {
                // Focus-retention counterpart (see playbackRequested above): the
                // turn terminated without handing off to playback (server error,
                // empty transcription, user cancel, persona switch) — release the
                // focus stopRecording() retained through THINKING, exactly as the
                // unconditional abandon did for these paths before. A turn whose
                // playback entry is still pending (eSpeak synthesis in flight,
                // chunk-0 synthesis in flight) has playbackRequested=true and
                // keeps the focus until playback's own teardown abandons it.
                if (!playbackRequested) {
                    abandonAssistantFocus()
                }
                // M2: this turn is leaving THINKING — it no longer owns the state, so a
                // later hands-free-off toggle must not treat the stale owner as live.
                clearThinkingOwnerIfOwned(generation)
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

// ─── Image Vision Support (Fix: stage 1) ───────────────────────────────────
// Reads an app-owned image file and returns its base64-encoded bytes.
private suspend fun encodeImageToBase64(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext null
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("Vision", "Failed to encode image at $path", e)
            null
        }
    }
}

internal fun AssistantService.sendTextMessageToServer(inputText: String, currentPersona: Persona, attachments: List<Uri> = emptyList(), imageUri: Uri? = null) {
    if (inputText.isBlank() && attachments.isEmpty() && imageUri == null) return
    val startTime = System.currentTimeMillis()
    _voiceDuration.value = 0

    serviceScope.launch {
        val imagePath = imageUri?.let { saveMessageImage(it) }

        // A9: the display/history user message shows a concise filename list
        _messages.value = _messages.value + ChatMessage("user",
            if (attachments.isNotEmpty())
                "$inputText\n\n(Attached: ${attachments.mapNotNull { it.lastPathSegment }.joinToString(", ")})"
            else inputText,
            imagePath = imagePath
        )
        saveSettings()

        if (currentPersona.model.isBlank()) {
            _messages.value = _messages.value + ChatMessage("assistant", "Please choose a model for this persona in its settings.", isError = true)
            return@launch
        }

        val useDeviceVoice = currentPersona.voiceMode != VoiceMode.GATEWAY

        // A2/B1: capture the request identity (generation + persona)
        val generation = nextChatRequestSeq()
        val personaName = currentPersona.name

        var playbackRequested = false

        if (currentPersona.isCloud && isCloudModel(currentPersona.model)) {
            performCloudChat(inputText, currentPersona, useDeviceVoice, startTime, currentTurnInHistory = true, generation = generation, imagePath = imagePath)
            return@launch
        }

        // M2: this turn owns the THINKING state it sets here (see stopVadListening()).
        recordThinkingOwner(generation)
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
                         val directRes = performDirectOllamaChat(ollamaBase, actualModel, inputText, currentPersona, attachments = attachments, imagePath = imagePath, currentTurnInHistory = true, generation = generation)
                         // Fix #5: clean markdown for TTS on IO — the cleaned variant feeds
                         // the on-device engines; the chat bubble/history keep the original.
                         val cleanedForTts = cleanTextForTts(directRes.first)

                         // If it's a gateway voice mode: Stage-2 streaming TTS
                         if (currentPersona.voiceMode == VoiceMode.GATEWAY) {
                             return@withContext listOf(directRes.first, directRes.second, null, cleanedForTts)
                         }

                         return@withContext listOf(directRes.first, directRes.second, directRes.third, cleanedForTts)
                     }
                }

                val currentDateTime = getCurrentDateTimeString()
                val modelText = buildModelPrompt(inputText, attachments)
                val requestBuilder = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    addFormDataPart("text", "Current date and time: $currentDateTime\n\nQuestion: $modelText")
                    addFormDataPart("model", actualModel)
                    addFormDataPart("backend_url", bUrl)
                    addFormDataPart("temperature", currentPersona.temperature.toString())
                    addFormDataPart("top_p", currentPersona.topP.toString())
                    addFormDataPart("top_k", currentPersona.topK.toString())
                    addFormDataPart("repeat_penalty", currentPersona.repeatPenalty.toString())
                    addFormDataPart("num_ctx", currentPersona.numCtx.toString())
                    if (currentPersona.isTranslator) {
                        addFormDataPart("target_language", currentPersona.targetLanguage)
                    }
                }
                val requestBody = requestBuilder.build()

                val allGateways = _serverBases.value

                // S2: data (prompts/attachments) is only sent to the persona's configured
                // backend unless the persona explicitly opts into gateway failover.
                val preferredGateway = if (currentPersona.backendUrl.isNotBlank()) {
                    allGateways.find { it.name == displayServer || it.url == currentPersona.backendUrl }
                } else null

                val gwsToTry: List<ServerConfig> = if (currentPersona.allowGatewayFailover) {
                    // A3: only gateways currently healthy (or past a failure cooldown) are
                    // eligible for failover.
                    val workingGateways = allGateways.filter { isServerHealthyForRetry(it.url) }
                    buildList {
                        preferredGateway?.let { add(it) }
                        addAll(workingGateways.filter { it != preferredGateway })
                    }
                } else {
                    when {
                        preferredGateway != null -> listOf(preferredGateway)
                        currentPersona.backendUrl.isBlank() -> throw Exception(
                            "No gateway is configured for persona '${currentPersona.name}'. Set a Backend URL in its persona settings, or enable 'Allow Gateway Failover'."
                        )
                        else -> throw Exception(
                            "Gateway '${currentPersona.backendUrl}' for persona '${currentPersona.name}' is not in the configured gateway list. Check Settings, or enable 'Allow Gateway Failover'."
                        )
                    }
                }

                if (gwsToTry.isEmpty()) {
                    throw Exception("No working servers available. Check your connection in Settings.")
                }

                // C2: context enrichment (news / web search / RAG) is loop-invariant —
                // fetch it once, before the retry loop, instead of re-running SearXNG
                // queries and RAG retrievals on every failed gateway attempt. This also
                // guarantees identical injected context across failover attempts.
                val searchContext = if (isNewsRequest(inputText)) {
                    fetchNewsContext()
                } else if (currentPersona.webSearchEnabled) {
                    fetchWebSearchContext(inputText)
                } else ""

                // Step 3 — RAG retrieval: prepend uploaded-document matches alongside
                // any web search context. Graceful: "" when unconfigured or on failure.
                val ragContext = if (currentPersona.ragEnabled) fetchRagContext(inputText) else ""
                val contextPrefix = listOf(searchContext, ragContext).filter { it.isNotEmpty() }.joinToString("\n\n")

                var lastEx: Exception? = null
                for (gw in gwsToTry.distinct()) {
                    val base = gw.url
                    // Scale read timeout with maxTokens to handle long processing/reasoning
                    val currentClient = getDynamicClient(currentPersona, useStandard = false)

                    val finalRequestBody = if (contextPrefix.isNotEmpty()) {
                        val promptContext = "$contextPrefix\n\n"

                        // Rebuild request body with search context PREPENDED to input text
                        MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                            addFormDataPart("text", "${promptContext}Current date and time: $currentDateTime\n\nQuestion: $modelText")
                            addFormDataPart("model", actualModel)
                            addFormDataPart("backend_url", bUrl)
                            addFormDataPart("temperature", currentPersona.temperature.toString())
                            addFormDataPart("top_p", currentPersona.topP.toString())
                            addFormDataPart("top_k", currentPersona.topK.toString())
                            addFormDataPart("repeat_penalty", currentPersona.repeatPenalty.toString())
                            addFormDataPart("num_ctx", currentPersona.numCtx.toString())

                            if (currentPersona.isTranslator) {
                                addFormDataPart("target_language", currentPersona.targetLanguage)
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
                                matched?.let { statusMap[it.url] = "Online" }
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
                                // Fix #5: clean markdown for TTS on IO — the cleaned variant
                                // feeds the on-device engines (4th element); the chat
                                // bubble/history keep the original markdown.
                                val cleanedForTts = cleanTextForTts(rText)
                                return@withContext listOf(rText, null, null, cleanedForTts)
                            } else {
                                val rText = response.decodeTextHeader("X-Translated-Text-B64",
                                    response.decodeTextHeader("X-Response-Text-B64", "..."))
                                val bytes = response.body.bytes()
                                // Fix #5: clean markdown for TTS on IO (see the JSON branch).
                                val cleanedForTts = cleanTextForTts(rText)
                                return@withContext listOf(rText, null, if (useDeviceVoice) null else bytes, cleanedForTts)
                            }
                        }
                    } catch (e: Exception) {
                        if (call.isCanceled()) throw java.io.IOException("Cancelled")

                        // A3: mark failed (with cooldown).
                        val failureLabel = if (e is okhttp3.internal.http2.StreamResetException || (e.message?.contains("HTTP ") == true || e.message?.contains("Server error:") == true)) {
                            e.message?.take(30)
                        } else if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                            "connection/timeout"
                        } else {
                            e.message?.take(30)
                        }
                        withContext(Dispatchers.Main) {
                            val statusMap = _serverStatus.value.toMutableMap()
                            val matched = _serverBases.value.find { it.url == base } ?:
                                          _ollamaBaseUrls.value.find { it.name == base || it.url == base }
                            matched?.let { cfg ->
                                statusMap[cfg.url] = "failed: $failureLabel"
                                serverFailCooldownUntilMillis[cfg.url] = System.currentTimeMillis() + FAILED_COOLDOWN_MS
                            }
                            _serverStatus.value = statusMap
                        }

                        lastEx = e
                    }
                }
                throw lastEx ?: Exception("All connection attempts failed")
            }
            // A2/B1: apply the response only if the context is unchanged.
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Chat response discarded — superseded by a newer request (gen $generation)")
                return@launch
            }
            // A6: this flow's network call has finished. Clear the in-flight reference
            // before applying (or discarding for a persona change) so currentCall never
            // points at a completed call. Safe here because no newer request exists,
            // so currentCall cannot belong to another flow.
            currentCall = null
            if (!isChatContextCurrent(generation, personaName)) {
                android.util.Log.d("AssistantService", "Chat response discarded — active persona changed (gen $generation)")
                return@launch
            }
            val (rText, reasoning, bytes, cleanedForTts) = responseData as List<Any?>
            val responseTimeMs = System.currentTimeMillis() - startTime
            val audioPath = if (bytes != null && !useDeviceVoice) {
                val outFile = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
                outFile.writeBytes(bytes as ByteArray)
                outFile.absolutePath
            } else null

            // Stage-1 streaming: when this turn streamed, the trailing assistant
            // placeholder is already in the list with the content built up —
            // finalize it IN PLACE (single final write; never a second append).
            // H3: consume/finalize only OUR OWN placeholder (null ⇒ a newer turn owns it,
            // or this one was superseded — fall back to a plain append, never a wrong-index
            // in-place finalize).
            val streamedIdx = takeStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            if (streamedIdx != null && streamedIdx < _messages.value.size) {
                _messages.value = _messages.value.mapIndexed { i, m ->
                    if (i == streamedIdx) m.copy(
                        text = rText as String,
                        reasoning = reasoning as? String,
                        audioFilePath = audioPath,
                        responseTimeMs = responseTimeMs
                    ) else m
                }
            } else {
                _messages.value = _messages.value + ChatMessage(
                    "assistant", rText as String, reasoning as? String,
                    audioFilePath = audioPath, responseTimeMs = responseTimeMs
                )
            }
            saveSettings()

            if (useDeviceVoice) {
                playbackRequested = true
                playResponse(currentPersona, deviceText = cleanedForTts as String)
            } else if (audioPath != null) {
                playbackRequested = true
                playResponse(currentPersona, file = File(audioPath))
            } else if (currentPersona.voiceMode == VoiceMode.GATEWAY) {
                // Stage-2 streaming TTS: gateway mode with deferred synthesis.
                // The full text was NOT pre-synthesized; use the chunked
                // sequential pipeline (synthesize chunk 0, play, synthesize
                // chunk 1 while playing, etc.) for dramatically lower
                // first-audio latency on long responses.
                playbackRequested = true
                playChunkedTtsGateway(rText as String, currentPersona, ttsGeneration)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamCancelledByUserException) {
            // D2: Stop interrupted a mid-stream text turn — the trailing
            // placeholder already holds the partial text (updated per chunk);
            // persist it as genuine history. No TTS (partial only).
            // H3: ownership first (see the voice flow) — a superseded turn must not
            // persist its stale partial, and must not clear a newer turn's in-flight
            // placeholder/published text.
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Stream cancelled — superseded by a newer request; partial discarded")
            } else if (currentPersonaName == personaName && e.partialContent.isNotBlank()) {
                saveSettings()
            } else {
                removeBlankPlaceholderSvc(generation)
            }
            releaseStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            android.util.Log.d("AssistantService", "Stream cancelled by user — partial kept (${e.partialContent.length} chars)")
            currentCall = null
        } catch (e: StreamPersonaChangedException) {
            // Partial already written back into the previous persona's persisted
            // history inside performDirectOllamaChat — nothing to apply here.
            // H3: still ownership-gated, so an unwind from an older turn can never
            // clear a newer turn's in-flight placeholder or published text.
            releaseStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            android.util.Log.d("AssistantService", "Stream cancelled — persona changed; partial kept in previous persona history")
            currentCall = null
        } catch (e: Exception) {
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Chat request superseded by a newer request — discarding failure: ${e.message}")
            } else if (isUserCancellation(e)) {
                // A5: the user deliberately stopped this request — no error bubble.
                android.util.Log.d("AssistantService", "Chat request cancelled by user — no error bubble")
                currentCall = null
            } else {
                android.util.Log.e("AssistantService", "Chat failed", e)
                if (isChatContextCurrent(generation, personaName)) {
                    _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message ?: "Unknown error"}", isError = true)
                } else {
                    android.util.Log.d("AssistantService", "Persona changed during request — discarding failure message: ${e.message}")
                }
                currentCall = null
            }
        } finally {
            // H3: ownership-gated everywhere — an unwinding OLDER turn must never
            // remove/clear a NEWER turn's placeholder (that was the duplicated-bubble
            // path). Non-owner is a no-op; the owner clears once.
            removeBlankPlaceholderSvc(generation)
            releaseStreamedPlaceholderIfOwned(generation)
            clearStreamingTextIfOwned(generation)
            if (_state.value == AssistantState.THINKING && isChatRequestCurrent(generation)) {
                // Focus-retention counterpart (see playbackRequested above).
                if (!playbackRequested) {
                    abandonAssistantFocus()
                }
                // M2: leaving THINKING — release state ownership (see stopVadListening()).
                clearThinkingOwnerIfOwned(generation)
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

internal fun AssistantService.fetchModels(config: ServerConfig? = null) {
    val targets = if (config != null) listOf(config) else _ollamaBaseUrls.value
    if (targets.isEmpty()) return
    incrementModelFetchCount()

    serviceScope.launch(Dispatchers.IO) {
        // L3: the in-flight counter was incremented above, so EVERY exit from this
        // coroutine must release it. The completion block at the end of the body can throw
        // (and a cancellation skips it entirely), which used to leave _isLoadingModels stuck
        // true — the spinner would spin for the rest of the session. Mirroring
        // fetchCloudModels, the whole body is wrapped in try/finally with a single
        // Main-confined decrement point. No catch clause is needed here: the per-target loop
        // already contains its own failures, so nothing else in this body is expected to
        // throw, and swallowing surprises silently would hide them.
        try {

            // B3 (chat) fix: do NOT snapshot _serverStatus/_fetchedLocalModels at coroutine
            // start and write them back wholesale at the end — two overlapping fetchModels
            // calls for different servers would then let the second-finishing call clobber
            // the first's results with its own stale snapshot. Instead, each iteration
            // collects results for JUST its own targets, and the completion block updates
            // only those specific entries in the LIVE state (read-modify-write), so a
            // concurrent call for another server keeps its own updates untouched.
            val perTargetResults = linkedMapOf<String, Pair<String, List<String>?>>() // url -> (status, models or null)
    
            for (target in targets) {
                perTargetResults[target.url] = "Could not connect to server" to null
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
                                    perTargetResults[target.url] = "Online" to serverModels.map { "[${target.name}] $it" }
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
                    perTargetResults[target.url] = lastErrorMessage to null
                }
            }
    
            withContext(Dispatchers.Main) {
                // B3: apply only our targets' entries into the LIVE maps, never a stale
                // full-map snapshot, so an overlapping fetch for a different server's
                // results are preserved.
                var localModelsMap = _fetchedLocalModels.value.toMutableMap()
                for ((url, result) in perTargetResults) {
                    val (status, models) = result
                    val targetName = targets.firstOrNull { it.url == url }?.name ?: continue
                    if (models != null) {
                        localModelsMap[targetName] = models
                    } else {
                        localModelsMap.remove(targetName)
                    }
                    var statusMap = _serverStatus.value.toMutableMap()
                    statusMap[url] = status
                    _serverStatus.value = statusMap
                }
                _fetchedLocalModels.value = localModelsMap
                _availableModels.value = localModelsMap.values.flatten().distinct()
            }
        } finally {
            // L3/M1: the single decrement point for every exit of this coroutine — normal
            // completion, a throw, or a cancellation. Unlike fetchCloudModels this one is
            // reached with the counter Main-confined by construction (increment on Main,
            // decrement here on Main).
            withContext(Dispatchers.Main) { decrementModelFetchCount() }
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

private fun AssistantService.performCloudChat(text: String, persona: Persona, useDeviceVoice: Boolean = false, startTime: Long, currentTurnInHistory: Boolean, generation: Long, attachments: List<Uri> = emptyList(), imagePath: String? = null) {
    serviceScope.launch {
        // M2: this turn owns the THINKING state it sets here (see stopVadListening()).
        recordThinkingOwner(generation)
        _state.value = AssistantState.THINKING
        updateNotification("Thinking (Cloud)...")
        // Focus-retention guard (same contract as sendAudioToServer): a voice
        // turn that reaches here retained its focus through THINKING — release
        // it in the finally below if playback was never handed off.
        var playbackRequested = false
        try {
            val responseData = withContext(Dispatchers.IO) {
                val providerName = if (persona.model.startsWith("[") && persona.model.contains("] ")) {
                    persona.model.substring(1, persona.model.indexOf("]"))
                } else ""

                val apiSetting = (_cloudApis.value + _customCloudApis.value).find { it.name == providerName }
                    ?: throw Exception("No config for provider '$providerName'")

                if (apiSetting.apiKey.isBlank() && apiSetting.icon != "C") throw Exception("API Key for ${apiSetting.name} is missing")
                val request = buildCloudRequest(apiSetting, persona, text, currentTurnInHistory, attachments, imagePath)
                // A6: track the call so the Stop button can cancel cloud requests too
                // (previously execute() was called on an untracked Call).
                val call = getDynamicClient(persona).newCall(request)
                currentCall = call
                try {
                    call.execute().use { response ->
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
                        // Fix #5: clean markdown for TTS here, on IO — the cleaned variant
                        // feeds the on-device engines (3rd element); the chat bubble/history
                        // keep the original markdown.
                        val cleanedForTts = cleanTextForTts(responseText)
                        Triple(responseText, reasonText, cleanedForTts)
                    }
                } finally {
                    // A6: clear the in-flight reference on success AND failure — but only
                    // if it's still this flow's call (a newer request may have replaced it).
                    if (currentCall === call) currentCall = null
                }
            }
            val responseTimeMs = System.currentTimeMillis() - startTime
            // A2/B1: apply only if this is still the newest request for this persona.
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Cloud response discarded — superseded by a newer request (gen $generation)")
                return@launch
            }
            if (!isChatContextCurrent(generation, persona.name)) {
                android.util.Log.d("AssistantService", "Cloud response discarded — active persona changed (gen $generation)")
                return@launch
            }
            if (useDeviceVoice) {
                _messages.value = _messages.value + ChatMessage("assistant", responseData.first, responseData.second, responseTimeMs = responseTimeMs)
                saveSettings()
                playbackRequested = true
                playResponse(persona, deviceText = responseData.third)
            } else if (persona.voiceMode == VoiceMode.GATEWAY) {
                // M1: synthesis authenticates with the persona's gateway entry, so
                // resolve it first and make a resolution failure VISIBLE. Previously an
                // unmatched Backend URL fell back to a credential-less config, the
                // request 401'd, synthesizeWithGateway swallowed it as "no audio" and
                // the reply was appended but silently never spoken — a paid turn with
                // no audio and no explanation. The text still lands in the transcript
                // (nothing is thrown here), followed by an error bubble naming the
                // misconfiguration.
                val ttsGatewayResolved = findGatewayConfig(persona.backendUrl) != null
                val (audioBytes, _) = synthesizeWithGateway(responseData.first, persona)
                val audioPath = if (audioBytes != null) {
                    val outFile = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
                    outFile.writeBytes(audioBytes)
                    outFile.absolutePath
                } else null

                _messages.value = _messages.value + ChatMessage("assistant", responseData.first, responseData.second, audioFilePath = audioPath, responseTimeMs = responseTimeMs)
                saveSettings()

                if (audioPath != null) {
                    playbackRequested = true
                    playResponse(persona, file = File(audioPath))
                } else if (!ttsGatewayResolved) {
                    // No synthesis request was sent at all — say why, at the point of use.
                    _messages.value = _messages.value + ChatMessage(
                        "assistant",
                        "Error: no configured gateway matches this persona's Backend URL " +
                            "'${persona.backendUrl}', so the reply could not be spoken. Fix the Backend URL in " +
                            "the persona settings (or add the gateway in Servers), then resend.",
                        isError = true
                    )
                    saveSettings()
                }
            } else {
                _messages.value = _messages.value + ChatMessage("assistant", responseData.first, responseData.second, responseTimeMs = responseTimeMs)
                saveSettings()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isChatRequestCurrent(generation)) {
                android.util.Log.d("AssistantService", "Cloud chat request superseded — discarding failure: ${e.message}")
            } else if (isUserCancellation(e)) {
                // A5: the user deliberately stopped this request — no error bubble.
                android.util.Log.d("AssistantService", "Cloud chat request cancelled by user — no error bubble")
            } else {
                if (isChatContextCurrent(generation, persona.name)) {
                    _messages.value = _messages.value + ChatMessage("assistant", "Error: ${e.message}", isError = true)
                } else {
                    android.util.Log.d("AssistantService", "Persona changed during cloud request — discarding failure message: ${e.message}")
                }
            }
        } finally {
            if (_state.value == AssistantState.THINKING && isChatRequestCurrent(generation)) {
                // Focus-retention counterpart (see playbackRequested above): the
                // turn terminated without handing off to playback — release the
                // focus stopRecording() retained through THINKING. Playback-pending
                // turns (eSpeak synthesis, gateway synthesis in flight) keep it
                // until playback's own teardown abandons.
                if (!playbackRequested) {
                    abandonAssistantFocus()
                }
                // M2: leaving THINKING — release state ownership (see stopVadListening()).
                clearThinkingOwnerIfOwned(generation)
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
            }
        }
    }
}

private suspend fun AssistantService.performDirectOllamaChat(baseUrl: String, model: String, text: String, persona: Persona, currentTurnInHistory: Boolean, generation: Long, attachments: List<Uri> = emptyList(), imagePath: String? = null): Triple<String, String?, ByteArray?> {
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
    // Stage-1 streaming: NDJSON deltas so the UI can render the response as it
    // is generated. Each line carries message.content (and message.thinking
    // for reasoning models); the final line carries done=true plus usage stats.
    json.put("stream", true)

    // A-attachments: the model-facing prompt includes extracted file content. The
    // original `text` is still used for search/news below.
    val modelText = buildModelPrompt(text, attachments)

    val msgsArray = org.json.JSONArray()
    if (persona.isTranslator) {
        msgsArray.put(JSONObject().put("role", "system").put("content", "You are a translation engine. Translate the user's text into the requested target language. Return ONLY the translation, no explanations, no extra commentary."))
        msgsArray.put(JSONObject().put("role", "user").put("content", "Translate the following text to ${persona.targetLanguage}: $modelText"))
    } else {
        // Context injection
        val currentDateTime = getCurrentDateTimeString()
        var finalSystemPrompt = "Current date and time: $currentDateTime\n\n${persona.systemPrompt}"

        val searchContext = if (isNewsRequest(text)) {
            fetchNewsContext()
        } else if (persona.webSearchEnabled) {
            fetchWebSearchContext(text)
        } else ""

        // Step 3 — RAG retrieval: prepend uploaded-document matches alongside
        // any web search context. Graceful: "" when unconfigured or on failure.
        val ragContext = if (persona.ragEnabled) fetchRagContext(text) else ""

        val contextPrefix = listOf(searchContext, ragContext).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (contextPrefix.isNotEmpty()) {
            finalSystemPrompt = "$contextPrefix\n\n$finalSystemPrompt"
        }

        // Add system prompt first
        // S4: contains the full system prompt (plus injected search/news context) — debug only.
        if (BuildConfig.DEBUG) android.util.Log.d("AssistantService", "DEBUG SYSTEM PROMPT: $finalSystemPrompt")
        msgsArray.put(JSONObject().put("role", "system").put("content", finalSystemPrompt))

        // Budget-aware history selection
        val contextWindow = persona.numCtx // Fix #3 (sibling): was hardcoded 8192 — keep the client-side trim in sync with the num_ctx this request sends to the server
        val reservedOutput = persona.maxTokens.coerceAtLeast(1024)
        // Fix #6 (numCtx overflow): shared budget math + pre-flight refusal — see
        // resolveHistoryBudget(). Throws (and sends nothing) when the system prompt,
        // this message/attachment and the reserved output alone already exceed the
        // window, since no history trimming can make such a request fit.
        val budget = resolveHistoryBudget(contextWindow, reservedOutput, finalSystemPrompt, modelText)

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
        history.forEachIndexed { i, msg -> android.util.Log.d("AssistantService", "DEBUG history[$i] role=${msg.role} preview=${msg.text.take(100)}") }

        history.forEach { msg ->
            msgsArray.put(JSONObject().put("role", msg.role).put("content", msg.text))
        }

        // A1: the current user turn is ALWAYS appended — repeated inputs must reach
        // the model as new turns even when identical text exists earlier in history.
        val currentMsg = JSONObject().put("role", "user").put("content", modelText)
        val base64Image = encodeImageToBase64(imagePath)
        if (base64Image != null) {
            currentMsg.put("images", JSONArray().put(base64Image))
        }
        msgsArray.put(currentMsg)
    }

    json.put("messages", msgsArray)

    val payload = json.toString()
    if (BuildConfig.DEBUG) {
        Log.d("Vision", "Direct Ollama payload: ${payload.take(500)}... [length: ${payload.length}]")
    }

    val options = JSONObject()
    options.put("temperature", persona.temperature)
    options.put("top_p", persona.topP)
    options.put("top_k", persona.topK)
    options.put("repeat_penalty", persona.repeatPenalty)
    options.put("num_ctx", persona.numCtx)
    // Debug-only: per-request reasoning trace (fires once per direct-Ollama completion).
    // Kept gated — useful with the thinking-aware read-timeout multiplier in getDynamicClient.
    if (BuildConfig.DEBUG) android.util.Log.d("AssistantService", "DEBUG: persona.name=${persona.name} enableThinking=${persona.enableThinking} isKnownThinking=${isKnownThinkingModel(model)}")
    options.put("think", persona.enableThinking && isKnownThinkingModel(model))
    options.put("num_predict", persona.maxTokens)

    json.put("options", options)

    val requestBuilder = Request.Builder()
        .url(url)
        .post(json.toString().toRequestBody("application/json".toMediaType()))

    _ollamaBaseUrls.value.find { it.url == resolvedBackend }?.let { config ->
        when (config.effectiveAuthType) {
            AuthType.NONE -> { /* no Authorization header */ }
            AuthType.BASIC -> if (!config.username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password ?: ""))
            }
            AuthType.API_KEY -> if (!config.apiKey.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
            }
        }
    }

    // A6: track direct-Ollama calls so the Stop button cancels them too (previously
    // execute() was called on an untracked Call, so direct-Ollama never cancelled).
    val call = getDynamicClient(persona).newCall(requestBuilder.build())
    currentCall = call
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) throw Exception("Ollama error: ${response.code}")

            // Stage-1 streaming: read the NDJSON line by line. Each line carries
            // an incremental message.content delta (and message.thinking deltas
            // for reasoning models); the final line carries done=true plus
            // usage stats (eval_count / prompt_eval_count / total_duration).
            // Those stats are logged only — nothing in the app currently
            // consumes them (context budget is client-side estimateTokens;
            // cost tracking is cloud-API-only), so moving them to the last
            // NDJSON line breaks nothing.
            val source = response.body.source()
            val content = StringBuilder()
            val thinkingBuf = StringBuilder()
            var placeholderAppended = false

            fun appendPlaceholderIfNeeded() {
                if (placeholderAppended) return
                _messages.value = _messages.value + ChatMessage("assistant", "")
                // H3: record this generation as the placeholder's owner, so a
                // superseded turn unwinding later cannot claim/clear it.
                adoptStreamedPlaceholder(generation, _messages.value.size - 1)
                placeholderAppended = true
            }

            fun updatePlaceholder() {
                // H3: only ever update OUR OWN placeholder — a stale turn must not
                // rewrite a newer turn's in-progress bubble (that produced the
                // "content jumps to the other turn's partial text" symptom).
                if (!ownsStreamedPlaceholderSvc(generation)) return
                val idx = streamedPlaceholderIndex ?: return
                val current = _messages.value.toMutableList()
                if (idx in current.indices) {
                    current[idx] = current[idx].copy(
                        text = content.toString(),
                        reasoning = thinkingBuf.toString().ifBlank { null }
                    )
                    _messages.value = current
                }
            }

            fun removeBlankPlaceholder() {
                // H3: gated on recorded ownership — the old unconditional body cleared
                // streamedPlaceholderIndex even when the index belonged to a newer
                // turn, which stranded that turn's empty bubble in the transcript.
                // Recorded (not currency) so this turn can still drop its OWN blank
                // bubble on a mid-stream network failure even if it was superseded.
                if (!ownsStreamedPlaceholderRecord(generation)) return
                val idx = streamedPlaceholderIndex ?: return
                val current = _messages.value.toMutableList()
                if (idx in current.indices && current[idx].text.isBlank()) {
                    current.removeAt(idx)
                    _messages.value = current
                }
                releaseStreamedPlaceholderIfOwned(generation)
            }

            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = JSONObject(line)
                    if (obj.optBoolean("done", false)) {
                        if (BuildConfig.DEBUG) android.util.Log.d(
                            "AssistantService",
                            "Ollama stream done: eval_count=${obj.optInt("eval_count", -1)}, " +
                                "prompt_eval_count=${obj.optInt("prompt_eval_count", -1)}, " +
                                "total_duration_ms=${obj.optLong("total_duration", -1L) / 1_000_000L}"
                        )
                        break
                    }
                    val msgObj = obj.optJSONObject("message") ?: continue
                    val chunk = msgObj.optString("content", "")
                    val thinkChunk = if (persona.enableThinking) msgObj.optString("thinking", "") else ""
                    if (chunk.isEmpty() && thinkChunk.isEmpty()) continue

                    // Per-chunk generation guard: Stop pressed or a newer request
                    // started mid-stream — cancel and surface the partial to the
                    // caller (D2: the partial is kept as real history).
                    if (!isChatRequestCurrent(generation)) {
                        call.cancel()
                        throw StreamCancelledByUserException(content.toString(), thinkingBuf.toString())
                    }
                    // Per-chunk persona guard: persona switched mid-stream —
                    // cancel; the partial is written back into the OLD persona's
                    // persisted history so no empty stub is stranded there.
                    if (!isChatContextCurrent(generation, persona.name)) {
                        call.cancel()
                        val idx = streamedPlaceholderIndexIfRecorded(generation)
                        if (idx != null) {
                            val saved = settingsManager.getPersonaMessages(persona.name) ?: emptyList()
                            if (idx < saved.size) {
                                if (content.isBlank()) {
                                    settingsManager.savePersonaMessages(persona.name, saved.filterIndexed { i, _ -> i != idx })
                                } else {
                                    settingsManager.savePersonaMessages(persona.name, saved.mapIndexed { i, m ->
                                        if (i == idx) m.copy(text = content.toString(), reasoning = thinkingBuf.toString().ifBlank { null }) else m
                                    })
                                }
                            }
                        }
                        // H3: ownership-gated clears (defensive — generation is still
                        // current in this branch, but every placeholder write/clear now
                        // funnels through the owned helpers).
                        releaseStreamedPlaceholderIfOwned(generation)
                        clearStreamingTextIfOwned(generation)
                        throw StreamPersonaChangedException(content.toString(), thinkingBuf.toString())
                    }

                    content.append(chunk)
                    if (thinkChunk.isNotEmpty()) thinkingBuf.append(thinkChunk)

                    // Progressive display: update the overlay AND the in-place
                    // trailing message. NOTE: _messages updates here are the
                    // streaming placeholder only — the single final append still
                    // happens in the caller's apply block, guarded as always.
                    appendPlaceholderIfNeeded()
                    updatePlaceholder()
                    // H3: publish the overlay text only while OUR generation still owns
                    // the slot — a superseded turn must not flash its partial over a
                    // newer turn's bubble (per-chunk guard above already throws, this
                    // closes the race between that check and this write). Recording the
                    // owner here is what lets the matching clear be ownership-gated.
                    if (isChatRequestCurrent(generation)) publishStreamingText(generation, content.toString())
                }
            } catch (e: java.io.IOException) {
                if (call.isCanceled()) {
                    // Stop pressed mid-read: hand the partial back so the
                    // caller's cancellation branch can persist it (D2).
                    clearStreamingTextIfOwned(generation)
                    throw StreamCancelledByUserException(content.toString(), thinkingBuf.toString())
                }
                // Generic stream failure (network drop mid-generation): drop a
                // blank placeholder, keep any partial text in place, and let
                // the error-bubble flow handle the failure.
                removeBlankPlaceholder()
                clearStreamingTextIfOwned(generation)
                throw e
            }

            return Triple(content.toString(), thinkingBuf.toString().ifBlank { null }, null)
        }
    } finally {
        // A6: clear the in-flight reference on success AND failure — but only if it's
        // still this flow's call (a newer request may have replaced currentCall).
        if (currentCall === call) currentCall = null
    }
}

// Stage-1 streaming: the NDJSON loop surfaces these instead of a bare
// IOException so the caller can persist the partial response (D2) — user-stop
// keeps the partial (persona-unchanged guard applies at the catch site);
// persona-switch writes the partial back into the previous persona's persisted
// history inside performDirectOllamaChat and discards here.
internal class StreamCancelledByUserException(val partialContent: String, val partialThinking: String) : java.io.IOException("Cancelled")
internal class StreamPersonaChangedException(val partialContent: String, val partialThinking: String) : java.io.IOException("Persona changed mid-stream")

private suspend fun AssistantService.buildCloudRequest(api: CloudApiSetting, persona: Persona, text: String, currentTurnInHistory: Boolean, attachments: List<Uri> = emptyList(), imagePath: String? = null): Request {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val json = JSONObject()
    
    val currentDateTime = getCurrentDateTimeString()
    var finalSystemPrompt = "Current date and time: $currentDateTime\n\n${persona.systemPrompt}"

    val searchContext = if (isNewsRequest(text)) {
        fetchNewsContext()
    } else if (persona.webSearchEnabled) {
        fetchWebSearchContext(text)
    } else ""

    // Step 3 — RAG retrieval: prepend uploaded-document matches alongside
    // any web search context. Graceful: "" when unconfigured or on failure.
    val ragContext = if (persona.ragEnabled) fetchRagContext(text) else ""

    val contextPrefix = listOf(searchContext, ragContext).filter { it.isNotEmpty() }.joinToString("\n\n")
    if (contextPrefix.isNotEmpty()) {
        finalSystemPrompt = "$contextPrefix\n\n$finalSystemPrompt"
    }

    // A-attachments: the model-facing prompt includes extracted file content. The
    // original `text` is still used for search/news above.
    val modelText = buildModelPrompt(text, attachments)

    // Budget-aware history selection
    val contextWindow = persona.numCtx // Fix #3: was hardcoded 128000 — the persona's own Context Window Size setting is now the trimming budget 
    val reservedOutput = persona.maxTokens.coerceAtLeast(1024)
    // Fix #6 (numCtx overflow): same shared budget math + pre-flight refusal as the
    // direct-Ollama path — see resolveHistoryBudget(). A cloud request whose fixed
    // parts don't fit the persona's Context Window Size is refused the same way.
    val budget = resolveHistoryBudget(contextWindow, reservedOutput, finalSystemPrompt, modelText)
    
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

    val base64Image = encodeImageToBase64(imagePath)

    return when (api.icon) {
        "A" -> {
            json.put("model", actualModel).put("max_tokens", persona.maxTokens).put("system", finalSystemPrompt)
            val msgArray = org.json.JSONArray()
            // Anthropic requires the conversation to start with 'user' and strictly
            // alternate roles. A1: same-role turns are MERGED (never silently
            // skipped), and the current user turn is always appended below.
            var lastRole = ""
            fun appendAnthropicTurn(role: String, turnText: String, imgB64: String? = null) {
                if (turnText.isBlank() && imgB64 == null) return
                if (role == lastRole && msgArray.length() > 0 && imgB64 == null) {
                    val prev = msgArray.getJSONObject(msgArray.length() - 1)
                    val content = prev.get("content")
                    if (content is String) {
                        prev.put("content", content + "\n\n" + turnText)
                    } else if (content is JSONArray) {
                        content.put(JSONObject().put("type", "text").put("text", "\n\n" + turnText))
                    }
                } else {
                    val msgObj = JSONObject().put("role", role)
                    if (imgB64 != null) {
                        val contentArr = JSONArray()
                        contentArr.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imgB64)))
                        contentArr.put(JSONObject().put("type", "text").put("text", turnText))
                        msgObj.put("content", contentArr)
                    } else {
                        msgObj.put("content", turnText)
                    }
                    msgArray.put(msgObj)
                    lastRole = role
                }
            }
            history.dropWhile { it.role != "user" }.forEach { msg -> appendAnthropicTurn(msg.role, msg.text) }
            // A1: the current user turn always reaches the model.
            appendAnthropicTurn("user", modelText, base64Image)
            json.put("messages", msgArray)
            Request.Builder().url("$baseUrl/v1/messages").header("x-api-key", api.apiKey).header("anthropic-version", "2023-06-01").header("content-type", "application/json").post(json.toString().toRequestBody(mediaType)).build()
        }
        "G" -> {
            val contents = org.json.JSONArray()
            // Gemini requires user/model alternation too. A1: same-role turns are
            // merged as extra parts (never skipped), and the current user turn is
            // always appended below.
            fun appendGeminiTurn(role: String, turnText: String, imgB64: String? = null) {
                if (turnText.isBlank() && imgB64 == null) return
                val geminiRole = if (role == "assistant") "model" else "user"
                val parts = JSONArray()
                if (imgB64 != null) {
                    parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imgB64)))
                }
                parts.put(JSONObject().put("text", turnText))

                if (contents.length() > 0) {
                    val last = contents.getJSONObject(contents.length() - 1)
                    if (last.getString("role") == geminiRole) {
                        val lastParts = last.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            lastParts.put(parts.get(i))
                        }
                        return
                    }
                }
                contents.put(JSONObject().put("role", geminiRole).put("parts", parts))
            }
            history.forEach { appendGeminiTurn(it.role, it.text) }
            // A1: the current user turn always reaches the model.
            appendGeminiTurn("user", modelText, base64Image)
            json.put("contents", contents)

            val genConfig = JSONObject()
            genConfig.put("maxOutputTokens", persona.maxTokens)
            json.put("generationConfig", genConfig)

            // Add system instruction for Gemini if needed
            val sysInst = JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", finalSystemPrompt)))
            json.put("system_instruction", sysInst)

            // S3: pass the key via the x-goog-api-key header
            Request.Builder()
                .url("$baseUrl/v1beta/models/$actualModel:generateContent")
                .header("x-goog-api-key", api.apiKey)
                .post(json.toString().toRequestBody(mediaType))
                .build()
        }
        else -> {
            json.put("model", actualModel).put("max_tokens", persona.maxTokens)
            val msgs = org.json.JSONArray().put(JSONObject().put("role", "system").put("content", finalSystemPrompt))
            history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
            
            val currentMsg = JSONObject().put("role", "user")
            if (base64Image != null) {
                val contentArr = JSONArray()
                contentArr.put(JSONObject().put("type", "text").put("text", modelText))
                contentArr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image")))
                currentMsg.put("content", contentArr)
            } else {
                currentMsg.put("content", modelText)
            }
            msgs.put(currentMsg)
            
            json.put("messages", msgs)
            val payload = json.toString()
            if (BuildConfig.DEBUG) {
                Log.d("Vision", "Request payload: ${payload.take(500)}... [length: ${payload.length}]")
            }
            val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
            Request.Builder().url(url).header("Authorization", "Bearer ${api.apiKey}").post(json.toString().toRequestBody(mediaType)).build()
        }
    }
}

private fun AssistantService.parseCloudResponse(api: CloudApiSetting, persona: Persona, body: String): Triple<String, String?, UsageInfo> {
    // S4: logs the first 500 chars of the model response (conversation content) — debug only.
    if (BuildConfig.DEBUG) android.util.Log.d("UsageTracking", "Parsing response from ${api.name}: ${body.take(500)}")
    val json = JSONObject(body)
    var text = ""; var reasoning: String? = null; var pTokens = 0; var cTokens = 0
    when (api.icon) {
        "A" -> {
            // Anthropic Messages API: content is an array of blocks. Extended-thinking
            // Claude models emit a "thinking" block before the "text" block — reading
            // index 0 alone would grab the wrong block or throw on .getString("text").
            // Iterate all blocks: concatenate text blocks, capture thinking into reasoning.
            val content = json.getJSONArray("content")
            val textParts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> textParts.add(block.optString("text"))
                    "thinking" -> reasoning = block.optString("thinking")
                }
            }
            text = textParts.joinToString("")
            if (json.has("usage")) {
                val usage = json.getJSONObject("usage")
                pTokens = usage.optInt("input_tokens", 0)
                cTokens = usage.optInt("output_tokens", 0)
            }
        }
        "G" -> {
            // Gemini: check for safety-blocked responses before parsing candidates.
            // Blocked prompt: top-level promptFeedback.blockReason is present.
            // Blocked response: candidates may be empty, or candidates[0].finishReason == "SAFETY".
            if (json.has("promptFeedback")) {
                val feedback = json.optJSONObject("promptFeedback")
                val reason = feedback?.optString("blockReason")
                if (!reason.isNullOrBlank()) {
                    throw SafetyBlockedException("Gemini blocked the prompt: $reason")
                }
            }
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw SafetyBlockedException("Gemini returned no candidates (possibly blocked by safety filters)")
            }
            val candidate = candidates.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason")
            if (finishReason == "SAFETY") {
                throw SafetyBlockedException("Gemini response blocked by safety filters")
            }
            // Multi-part parsing: iterate all parts and concatenate text parts,
            // rather than assuming a single part at index 0.
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val textParts = mutableListOf<String>()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)
                    val partText = part?.optString("text")
                    if (!partText.isNullOrBlank()) {
                        textParts.add(partText)
                    }
                }
            }
            text = textParts.joinToString("")
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

internal fun AssistantService.isKnownThinkingModel(modelName: String): Boolean {
    val name = modelName.lowercase()
    // "v4-pro": DeepSeek's R1 line is being delisted (completes 2026-10-10 per
    // DeepSeek's announcement) and V4-Pro's built-in Thinking mode is the official
    // R1 replacement. Deliberately NOT matched: the flash/vision variants
    // (deepseek-v4-flash, deepseek-v4-flash-vision-exp) — they are the fast/
    // non-reasoning tier and Ollama rejects think:true for them.
    return name.contains("deepseek-r1") || name.contains("deepseek-reasoner") || name.contains("-r1") ||
        name.contains("v4-pro")
}

// L6: the OpenRouter pricing table is keyed by the provider's own model ids
// ("anthropic/claude-3-5-sonnet-20241022"), while the active persona's model label is the
// app's display form ("[Anthropic] claude-3-5-sonnet-latest"). Matching the two is a
// keyword heuristic and stays one (the cost is display/telemetry only), but it used to be
// non-deterministic: `maxByOrNull` keeps the FIRST maximum, so whenever two entries scored
// the same — "openai/gpt-4o" and "openai/gpt-4o-mini" both match the bare label "gpt-4o"
// on the tokens "gpt" and "4o" — the winner was decided by map iteration order, i.e. one
// model's prices could be charged to another. It also threw on a hand-typed label with an
// unclosed bracket ("[foo"), inside the response path. Extracted (internal, no Android
// surface) so PricingMatchTest can pin the ordering down with a synthetic table.
internal fun matchPricingEntry(
    pricingMap: Map<String, ModelPricing>,
    modelLabel: String
): Pair<String, ModelPricing>? {
    val actualModelId = modelLabel.substringAfter("] ").lowercase().trim()
    val closingBracket = modelLabel.indexOf(']')
    val provider = if (modelLabel.startsWith("[") && closingBracket > 1) {
        modelLabel.substring(1, closingBracket).lowercase().trim()
    } else ""

    val ignored = setOf("latest", "chat", "v1", "v2", "v3", "online")
    val modelParts = actualModelId.split("-", ".", "_").filter { it.isNotBlank() && it !in ignored }
    val vendorParts = provider.split("-", ".", "_").filter { it.isNotBlank() && it !in ignored }
    val normalize = { part: String -> part.replace("-", "").replace("_", "").replace(".", "") }
    val modelTokens = modelParts.map(normalize).filter { it.isNotBlank() }
    val allParts = (modelTokens + vendorParts.map(normalize)).filter { it.isNotBlank() }
    if (allParts.isEmpty()) return null

    // "/" is deliberately kept in the normalized id: it separates the provider prefix from
    // the model portion, which is what the specificity test below compares against.
    val normalizedModelId = modelTokens.joinToString("")

    return pricingMap.entries
        .map { entry ->
            val normalizedId = normalize(entry.key.lowercase())
            PricingCandidate(
                key = entry.key,
                pricing = entry.value,
                // Scoring is unchanged: how many of the label's tokens appear anywhere in
                // the entry's id, needing at least two hits unless the label is one token.
                score = allParts.count { normalizedId.contains(it) },
                normalizedId = normalizedId
            )
        }
        .filter { it.score >= minOf(2, allParts.size) }
        .maxWithOrNull(
            compareBy<PricingCandidate> { it.score }
                // The entry that IS this model beats one that merely shares tokens: an exact
                // match of the model portion, then an entry containing the whole label.
                .thenBy {
                    val entryModelId = it.normalizedId.substringAfterLast("/")
                    when {
                        entryModelId == normalizedModelId -> 2
                        it.normalizedId.contains(normalizedModelId) -> 1
                        else -> 0
                    }
                }
                // Fewer qualifiers wins, so a dated/annotated entry loses to the canonical
                // one ("openai/gpt-4o" over "openai/gpt-4o-mini-2024-07-18").
                .thenBy { -it.normalizedId.length }
                // Final, total tie-break — the answer no longer depends on map order.
                .thenByDescending { it.key }
        )
        ?.let { it.key to it.pricing }
}

private data class PricingCandidate(
    val key: String,
    val pricing: ModelPricing,
    val score: Int,
    val normalizedId: String
)

private fun AssistantService.calculateCost(persona: Persona, promptTokens: Int, completionTokens: Int): Double {
    val model = persona.model.lowercase()
    val actualModelId = persona.model.substringAfter("] ").lowercase().trim()

    // Attempt best-effort match with OpenRouter pricing using keyword scoring
    val matchedEntry = matchPricingEntry(settingsManager.getModelPricing(), persona.model)
    if (matchedEntry != null) {
        val (matchedKey, pricing) = matchedEntry
        val cost = (promptTokens * pricing.prompt) + (completionTokens * pricing.completion)
        android.util.Log.d("UsageTracking", "Cost calc using dynamic rate for $actualModelId ($matchedKey=${pricing}): $cost")
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("PricingSync", "Sync failed", e)
        }
    }
}

private fun Response.decodeTextHeader(name: String, fallback: String): String {
    val encoded = this.header(name) ?: return fallback
    return try { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { fallback }
}

// Fix #6 (numCtx overflow): the ONE place the client-side history budget is computed,
// shared by the two budgeted request builders — performDirectOllamaChat and
// buildCloudRequest. The gateway/multipart paths deliberately don't come through here:
// they hand the request to the server, which manages its own context window.
//
// The budget is what is left for history once the parts that cannot be trimmed are
// accounted for: the output tokens reserved for the reply, the system prompt (plus any
// injected news/search/RAG context) and the current message (with attachment text already
// inlined by buildModelPrompt). When those fixed parts alone already exceed the persona's
// Context Window Size, the budget goes negative — forcing a history floor there would ADD
// history on top of an already-overflowing request and make server-side truncation worse,
// not better, so zero history remains the fallback for a budget of exactly 0.
//
// A negative budget, however, cannot be fixed by dropping history: by definition no
// history is involved yet. Previously that request was sent anyway and the server
// silently truncated the system prompt, the message or the attachment, leaving the user
// with a degraded or nonsensical answer and no explanation. So instead we refuse to send
// it and say exactly what happened and what to change. The cause is deliberately not
// distinguished — a long system prompt, a long message, a large attachment or any
// combination of them are one and the same problem here, and take the same fix — which is
// why the check lives in this single shared helper rather than at each call site.
private fun AssistantService.resolveHistoryBudget(contextWindow: Int, reservedOutput: Int, systemPrompt: String, modelText: String): Int {
    val systemPromptTokens = estimateTokens(systemPrompt)
    val modelTextTokens = estimateTokens(modelText)
    val promptTokens = systemPromptTokens + modelTextTokens
    val rawBudget = contextWindow - reservedOutput - promptTokens
    if (rawBudget < 0) {
        val fixedTokens = promptTokens + reservedOutput
        android.util.Log.w("AssistantService", "numCtx overflow — refusing request: contextWindow=$contextWindow, reservedOutput=$reservedOutput, systemPrompt=$systemPromptTokens, currentMessage=$modelTextTokens, fixedTotal=$fixedTokens tokens")
        throw Exception(
            "Your message and system prompt (~$fixedTokens tokens) exceed this persona's Context Window Size ($contextWindow). " +
                "Try a shorter message, a smaller attachment, or increase Context Window Size in this persona's settings."
        )
    }
    return rawBudget
}

private fun AssistantService.estimateTokens(text: String): Int = (text.length / 4.0).toInt().coerceAtLeast(1)
