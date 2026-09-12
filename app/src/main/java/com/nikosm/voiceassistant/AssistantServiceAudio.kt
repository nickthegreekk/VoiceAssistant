package com.nikosm.voiceassistant

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// A3: monotonically increasing utterance counter — two calls can never share an ID
// (a time-based ID could collide for two calls in the same millisecond).
private var ttsUtteranceCounter: Long = 0

fun AssistantService.toggleEarpieceMode() {
    _earpieceMode.value = !earpieceMode.value
}

/**
 * Requests audio focus for assistant playback (A1/A2 fix). The request object is reused
 * across calls and only rebuilt when the earpiece/speaker mode changes; the focus result
 * is honored instead of discarded.
 *
 * @return true when focus was granted (playback may proceed); false when the request
 * failed or is still delayed. Note: callers do not check this return value yet.
 */
internal fun AssistantService.requestAssistantFocus(): Boolean {
    val isEarpiece = earpieceMode.value
    // A2: reuse the existing request while the mode is unchanged instead of building
    // (and leaking) a new one on every call. Rebuild only when the usage differs, so
    // attributes always match the target routing. The request lives on the service
    // instance, so a recreated service builds a fresh one wired to its own listener.
    val wantedUsage = if (isEarpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
    val request = audioFocusRequest?.takeIf { it.audioAttributes.usage == wantedUsage }
        ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(wantedUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // NOTE (review A1): delayed focus gain is only honored for AUDIOFOCUS_GAIN,
            // so this flag is effectively inert with GAIN_TRANSIENT. Left unchanged
            // pending a separate decision.
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    val previous = audioFocusRequest
    if (previous != null && previous !== request) {
        // A2: abandon the superseded request instead of overwriting it while it is
        // still registered with the framework (the old leak).
        audioManager.abandonAudioFocusRequest(previous)
    }
    audioFocusRequest = request
    // A1: honor the request result instead of discarding it.
    return when (val result = audioManager.requestAudioFocus(request)) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
            if (isEarpiece) {
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
                else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            }
            true
        }
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
            android.util.Log.w("AssistantService", "requestAssistantFocus: focus DELAYED (result=$result, isEarpiece=$isEarpiece) — request left pending; audio mode not switched until AUDIOFOCUS_GAIN arrives")
            false
        }
        else -> { // AudioManager.AUDIOFOCUS_REQUEST_FAILED
            android.util.Log.e("AssistantService", "requestAssistantFocus: focus request FAILED (result=$result, isEarpiece=$isEarpiece) — audio mode not switched; playback must not start")
            if (previous !== request) audioFocusRequest = null
            false
        }
    }
}

/**
 * B2: identity-guarded focus abandonment for engine cleanup paths. Captures the
 * AudioFocusRequest instance this cleanup is about to abandon, abandons exactly
 * that instance, and only clears the shared audioFocusRequest field if it still
 * holds that same instance — a cleanup running for an already-superseded request
 * cannot clobber a newer one that requestAssistantFocus() has since installed
 * (the same cross-engine protection stopAudio() applies to currentAudioTrack /
 * currentPlayer).
 */
internal fun AssistantService.abandonAssistantFocus() {
    val request = audioFocusRequest ?: return
    audioManager.abandonAudioFocusRequest(request)
    if (audioFocusRequest === request) audioFocusRequest = null
}

fun AssistantService.speakTextOnDevice(text: String) {
    if (!ttsReady || silenced.value) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    
    if (!requestAssistantFocus()) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    _state.value = AssistantState.SPEAKING
    updateNotification("Speaking (Device)...")
    
    val params = android.os.Bundle()
    params.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
    
    tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        // A3: only the most recently issued utterance may clean up. The binder-thread
        // check filters stale callbacks; the Main-thread re-check covers a newer
        // speakTextOnDevice() call that started while this cleanup was queued.
        override fun onDone(id: String?) {
            if (id == null || id != currentUtteranceId) return
            serviceScope.launch(Dispatchers.Main) {
                if (id != currentUtteranceId) return@launch
                currentUtteranceId = null
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                abandonAssistantFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            }
        }
        override fun onError(id: String?) {
            // A3: same ID gate as onDone — a stale error callback must not clean up.
            // The ID-gate inside onDone filters superseded utterances, so a failure
            // that reaches here is genuine and currently relevant — log it before
            // delegating for cleanup so TTS synthesis failures are debuggable.
            Log.w("TTS", "TTS synthesis failed for utterance $id")
            onDone(id)
        }
    })
    
    // A3: unique ID per call (an incrementing counter can never collide, unlike a
    // time-based ID within the same millisecond).
    val utteranceId = "assistant_msg_${++ttsUtteranceCounter}"
    currentUtteranceId = utteranceId
    // A4: if the engine rejects the call (returns ERROR), no utterance is queued and
    // neither onDone nor onError will ever fire — clean up immediately instead of
    // staying stuck in SPEAKING with nothing to clear it.
    val speakResult = tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    if (speakResult == android.speech.tts.TextToSpeech.ERROR) {
        currentUtteranceId = null
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        abandonAssistantFocus()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
    }
}

internal fun AssistantService.speakWithEspeak(text: String, persona: Persona) {
    stopAudio()
    // C4: check silence BEFORE any synthesis work — when silent mode is on, skip the
    // entire pipeline (engine initialization, voice selection, setVoice, synthesize)
    // since the result would be discarded anyway. Same synchronous early-bail pattern
    // as speakTextOnDevice's silenced check.
    if (silenced.value) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    val engine = espeakEngine ?: run {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    
    serviceScope.launch(Dispatchers.IO) {
        val voiceName = when (persona.targetLanguage.lowercase()) {
            "greek" -> "el"
            "french" -> "fr"
            "german" -> "de"
            "spanish" -> "es"
            "italian" -> "it"
            "japanese" -> "ja"
            "korean" -> "ko"
            "chinese" -> "cmn"
            "russian" -> "ru"
            "hebrew" -> "he"
            "dutch" -> "nl"
            "turkish" -> "tr"
            "arabic" -> "ar"
            "hindi" -> "hi"
            "portuguese" -> "pt"
            else -> "en"
        }
        // A6: if the voice switch fails, eSpeak keeps whatever voice was previously
        // active (wrong language/accent). There is no user-facing feedback mechanism
        // for this, so synthesis still proceeds — but the failure is now visible in
        // logs instead of being completely silent.
        if (!engine.setVoice(voiceName)) {
            android.util.Log.w("AssistantService", "Failed to set eSpeak voice to '$voiceName', falling back to current voice")
        }
        val samples = engine.synthesize(text)
        
        withContext(Dispatchers.Main) {
            if (!requestAssistantFocus()) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return@withContext
            }
            _state.value = AssistantState.SPEAKING
            updateNotification("Speaking (eSpeak)...")

            val sampleRate = engine.getSampleRate()
            if (sampleRate <= 0 || samples.isEmpty()) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return@withContext
            }

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(samples.size * 2, minBufferSize)

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (earpieceMode.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                android.util.Log.e("AssistantService", "speakWithEspeak: AudioTrack STATE_UNINITIALIZED (minBufferSize=$minBufferSize, bufferSize=$bufferSize), bailing.")
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return@withContext
            }

            currentAudioTrack = audioTrack
            audioTrack.play()

            // A7: genuine-completion detection via the playback-position marker —
            // no sleep-based estimate. Marker position = total frames actually written
            // (PCM16 mono = 2 bytes/frame) so onMarkerReached fires precisely when real
            // playback finishes. The callback runs on AudioTrack's handler thread, so
            // all state/focus work hops to Main via serviceScope.launch. The identity
            // guard (currentAudioTrack == audioTrack) preserves the B1/A12 guarantees:
            // a newer playback that released this track skips here, and a superseded
            // track can't double-release. If stopAudio() releases the track before the
            // marker fires, it simply never fires — no hanging coroutine.
            val totalFrames = samples.size / 2
            audioTrack.setNotificationMarkerPosition(totalFrames)
            audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    serviceScope.launch {
                        if (currentAudioTrack == audioTrack && audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                            if (assistantState.value == AssistantState.SPEAKING) {
                                _state.value = AssistantState.IDLE
                                updateNotification("Ready to help")
                                abandonAssistantFocus()
                                audioManager.mode = AudioManager.MODE_NORMAL
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
                            }
                            audioTrack.stop()
                            audioTrack.release()
                            currentAudioTrack = null
                        }
                    }
                }
                override fun onPeriodicNotification(track: AudioTrack) {}
            })

            withContext(Dispatchers.IO) {
                audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            }

            val durationMs = (samples.size.toFloat() / sampleRate * 1000).toInt()
            _voiceDuration.value = durationMs
        }
    }
}

internal fun AssistantService.playAudioFile(file: File) {
    stopAudio()
    if (silenced.value) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    if (!requestAssistantFocus()) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    _state.value = AssistantState.SPEAKING
    updateNotification("Speaking...")
    val player = MediaPlayer()
    try {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (earpieceMode.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(file.absolutePath)
        player.setOnCompletionListener {
            if (currentPlayer == it) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                it.release()
                currentPlayer = null
                _voiceDuration.value = 0
                abandonAssistantFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            }
        }
        // C1: prepare asynchronously so disk I/O + codec init don't block the main
        // thread. Callbacks are delivered on this thread's Looper (main). duration is
        // only valid once prepared, so it is read in the callback below.
        player.setOnPreparedListener { mp ->
            _voiceDuration.value = mp.duration
            mp.start()
        }
        // With prepareAsync(), preparation failures arrive here instead of the catch
        // block below. Same cleanup as the completion path; returning true consumes
        // the error so no spurious onCompletion follows.
        player.setOnErrorListener { mp, what, extra ->
            android.util.Log.e("AssistantService", "playAudioFile: MediaPlayer error what=$what extra=$extra")
            if (currentPlayer == mp) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                mp.release()
                currentPlayer = null
                _voiceDuration.value = 0
                abandonAssistantFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            } else {
                mp.release()
            }
            true
        }
        // Registered BEFORE prepareAsync() so stopAudio() can find and release() a
        // still-preparing player: release() is valid in any state (including Preparing)
        // and cancels the pending prepare, and no callbacks fire after release(). This
        // keeps the B1 guarantee that a newer playAudioFile() stops the older one.
        currentPlayer = player
        player.prepareAsync()
    } catch (e: Exception) {
        // prepareAsync() now runs after the currentPlayer registration; if it throws
        // synchronously, don't leave a dangling reference to the released player.
        if (currentPlayer === player) currentPlayer = null
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        player.release()
        abandonAssistantFocus()
    }
}

fun AssistantService.stopAudio() {
    // Stage-2: bump the TTS generation so all pending chunked-playback
    // onCompletion callbacks see a stale generation and halt atomically.
    ttsGeneration++
    currentPlayer?.let {
        val p = it
        currentPlayer = null
        try { if (p.isPlaying) p.stop() } catch (e: Exception) {} finally { p.release() }
    }
    currentAudioTrack?.let {
        try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } catch (e: Exception) {} finally { it.release() }
    }
    currentAudioTrack = null
    tts.stop()
    if (assistantState.value == AssistantState.SPEAKING) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
    }
    _voiceDuration.value = 0
    abandonAssistantFocus()
    audioManager.mode = AudioManager.MODE_NORMAL
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
}

fun AssistantService.stopEverything() {
    currentCall?.cancel()
    currentCall = null
    // Stop-semantics fix: invalidate any in-flight turn through the same generation
    // mechanism used for superseded requests. currentCall only tracks the main chat
    // request — standalone transcription/synthesis/RAG calls either don't set it or
    // run in phases where it is null. Bumping the sequence makes every existing
    // isChatRequestCurrent/isChatContextCurrent apply-gate (sendAudioToServer,
    // sendTextMessageToServer, performCloudChat) discard whatever the in-flight turn
    // eventually produces — no history append and, critically, no playResponse —
    // even when the underlying network work completes after Stop was pressed.
    nextChatRequestSeq()
    // Fix: stop an active manual recording — the red STOP button is reachable during
    // LISTENING, and this used to leave the MediaRecorder running: the mic stayed hot
    // after an explicit Stop, the partial file grew unbounded, and the next
    // startRecording() replaced the recorder reference without releasing the
    // orphaned one. The partial file is DELETED here (an explicit user-initiated
    // stop is not a completed turn — it must never be transcribed or sent).
    stopActiveRecording()
    outputFile?.delete()
    outputFile = null
    stopAudio()
    // Fix: SPEAKING belongs in the reset set — Stop from an active playback must
    // return to IDLE. stopAudio() above already resets SPEAKING when it observes
    // it, but the eSpeak path flips _state to SPEAKING from a background synthesis
    // coroutine whose only exit is the AudioTrack marker callback — a callback that
    // never fires once stopAudio() has released the track. A state flip landing
    // after stopAudio()'s internal check used to strand the UI on SPEAKING, where
    // the mic tap is a dead no-op (it only acts on IDLE/LISTENING). Re-checking
    // here, after teardown, closes that window: Stop from ANY active state
    // deterministically lands on IDLE.
    if (assistantState.value == AssistantState.THINKING || assistantState.value == AssistantState.LISTENING || assistantState.value == AssistantState.SPEAKING) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
    }
    _voiceDuration.value = 0
    abandonAssistantFocus()
    audioManager.mode = AudioManager.MODE_NORMAL
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
}

/**
 * Strips markdown formatting from text before on-device TTS synthesis.
 * Mirrors the server-side clean_text_for_tts() in tts_server.py so that the
 * eSpeak and System TTS engines don't read markup symbols aloud (**bold**,
 * *italic*, # headers, `backticks`). The Gateway/Kokoro path already gets this
 * treatment server-side, but on-device engines never did — until now.
 *
 * - **bold** / *italic* → keep enclosed text, strip the asterisks
 * - # / ## / etc. heading markers → removed
 * - ` / `` code markers → removed
 * - Excessive blank lines (3+) → collapsed to a single blank line
 */
fun cleanTextForTts(text: String): String {
    var cleaned = text
    // **bold** markers — keep the enclosed text, strip the asterisks
    cleaned = cleaned.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    // *italic* markers — keep the enclosed text, strip the asterisks
    cleaned = cleaned.replace(Regex("\\*(.+?)\\*"), "$1")
    // # / ## / ### heading markers (line-start, 1–6 hashes followed by whitespace)
    cleaned = cleaned.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    // Backtick code markers (single and triple)
    cleaned = cleaned.replace(Regex("`+"), "")
    // ASCII arrows — eSpeak/System TTS read "->" literally ("hyphen greater
    // than"). Space replacement so "A -> B" becomes "A B". Mirrors the
    // server-side clean_text_for_tts() arrow strip.
    cleaned = cleaned.replace(Regex("-->|->|=>|<-"), " ")
    // Unicode arrows (→ ← ↔ ⟶ etc., U+2190–21FF)
    cleaned = cleaned.replace(Regex("[\\u2190-\\u21FF]"), " ")
    // Emoji + misc symbols/dingbats + variation selectors + ZWJ + keycap +
    // invisible formatting (U+200B–200F). Surrogate-pair class covers all
    // astral-plane emoji incl. flags (regional indicators). Kokoro and the
    // on-device engines read these as garbage or skip them awkwardly.
    // Mirrors the server-side clean_text_for_tts() emoji strip.
    cleaned = cleaned.replace(
        Regex("[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]|[\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0E\\uFE0F\\u200B-\\u200F\\u20E3]"),
        ""
    )
    // Collapse leftover multi-spaces from the removals above
    cleaned = cleaned.replace(Regex(" {2,}"), " ")
    // Collapse 3+ consecutive newlines to a single blank line
    cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")
    return cleaned
}

// ─── Stage-2 streaming TTS: sentence-boundary splitter ─────────────────────
// Splits accumulated LLM output into TTS-sized chunks at natural sentence
// boundaries. Runs BEFORE cleanTextForTts() per chunk (cleaning collapses
// newlines and can join sentences; splitting first preserves boundaries).
//
// Guards:
//   - Decimal points: "3.14" — period followed by a digit, not a sentence end
//   - Abbreviations: Dr., Mr., Mrs., vs., e.g., i.e., etc., St., Inc., U.S.
//     (checked against a small blocklist before splitting at a period)
//   - Double newlines (paragraph breaks) are also valid split points
//
// Chunks shorter than minChunkChars are accumulated until they exceed it —
// avoids the HTTP round-trip overhead of 5-word chunks. The last chunk is
// always emitted even if short (it's the tail of the response).
internal fun splitIntoTtsChunks(text: String, minChunkChars: Int = 80): List<String> {
    if (text.isBlank()) return emptyList()

    // Abbreviations that end in a period but are NOT sentence boundaries.
    val abbreviations = setOf(
        "dr", "mr", "mrs", "ms", "vs", "e.g", "i.e", "etc", "st", "inc",
        "u.s", "no", "vol", "approx", "dept", "est", "fig", "gen", "gov",
        "sen", "rep", "prof", "rev", "hon", "lt", "col", "sgt", "capt"
    )

    // Sentence-ending punctuation followed by whitespace and a capital letter
    // or digit (heuristic for "actually the next sentence"). The lookbehind
    // allows . ! ? … and the lookahead checks it's a real boundary.
    val sentenceEnd = Regex(
        """(?<=[.!?…])\s+(?=[A-Z0-9"'(])""",
        setOf(RegexOption.MULTILINE)
    )

    // First: split into candidate sentences (naive split on sentence-end regex)
    val rawSentences = text.split(sentenceEnd).map { it.trim() }.filter { it.isNotBlank() }
    if (rawSentences.isEmpty()) return listOf(text.trim())

    // Second: merge adjacent sentences that were falsely split (abbreviation
    // or decimal guard — if the fragment before the split ends with a known
    // abbreviation pattern or a digit, it was NOT a sentence boundary)
    val sentences = mutableListOf<String>()
    var pending = rawSentences[0]
    for (i in 1 until rawSentences.size) {
        val lastWord = pending.substringAfterLast(" ").substringBeforeLast(".")
        val isAbbrev = lastWord.lowercase() in abbreviations ||
            pending.endsWith(".") && pending.lastOrNull()?.isDigit() == true
        if (isAbbrev) {
            pending = pending + " " + rawSentences[i]
        } else {
            sentences.add(pending)
            pending = rawSentences[i]
        }
    }
    sentences.add(pending)

    // Third: accumulate into chunks of at least minChunkChars
    val chunks = mutableListOf<String>()
    var chunk = ""
    for (sentence in sentences) {
        val candidate = if (chunk.isEmpty()) sentence else "$chunk $sentence"
        if (candidate.length >= minChunkChars) {
            chunks.add(candidate)
            chunk = ""
        } else {
            chunk = candidate
        }
    }
    // Emit the tail chunk even if below minimum (it's the end of the response)
    if (chunk.isNotBlank()) chunks.add(chunk)

    return chunks.ifEmpty { listOf(text.trim()) }
}

fun AssistantService.playResponse(persona: Persona, file: File? = null, deviceText: String? = null) {
    // deviceText arrives PRE-CLEANED: cleanTextForTts() now runs in the background
    // parse blocks (Dispatchers.IO) right after the LLM response is received, so the
    // Main thread never runs the regex passes (they used to jank on long responses).
    // The Gateway/Kokoro path is cleaned server-side and passes through here with
    // file != null, so it's unaffected.
    when (persona.voiceMode) {
        VoiceMode.NONE -> {
            _state.value = AssistantState.IDLE
            updateNotification("Ready to help")
        }
        VoiceMode.SYSTEM_TTS -> {
            if (deviceText != null) speakTextOnDevice(deviceText)
            else {
                if (file != null) playAudioFile(file)
                else {
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                }
            }
        }
        VoiceMode.BUNDLED_ESPEAK -> {
            if (deviceText != null) speakWithEspeak(deviceText, persona)
            else {
                if (file != null) playAudioFile(file)
                else {
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                }
            }
        }
        VoiceMode.GATEWAY -> {
            if (file != null) playAudioFile(file)
            else {
                if (deviceText != null) speakTextOnDevice(deviceText)
                else {
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                }
            }
        }
    }
}

// ─── Stage-2 streaming TTS: chunked sequential synthesis + playback ────────
//
// Sequential pipeline (confirmed decision): synthesize chunk 0, play it while
// synthesizing chunk 1, play chunk 1 while synthesizing chunk 2, etc.
// Ordering is inherently guaranteed - each chunk is played immediately after
// synthesis, before the next one starts. No slot-map complexity needed.
//
// SPEAKING lifetime: entered ONCE before chunk 0 starts, exited ONCE after
// the last chunk finishes. No SPEAKING→IDLE→SPEAKING flickering between
// chunks - the VAD collector and proximity barge-in both react to state
// transitions and see a single continuous window.
//
// stopAudio() is NEVER called between chunks (it would reset SPEAKING→IDLE
// mid-sequence and kill the player). Instead, the chunked player swaps the
// data source on the same MediaPlayer in its onCompletion callback.
//
// ttsGeneration counter: bumped by stopAudio() - Stop/barge-in/focus-loss
// all route through it. Each chunked player captures the generation before
// it starts; every onCompletion checks it. If the generation has advanced,
// the entire sequence is stale and must not continue.

// Stage-2 streaming TTS: sequential chunked synthesis + gapless playback.
// Returns the list of chunk file paths (for debugging) - playback is managed
// internally. SPEAKING spans the full sequence.
internal suspend fun AssistantService.playChunkedTtsGateway(
    fullText: String,
    persona: Persona,
    myTtsGeneration: Long
): List<String> {
    val chunks = splitIntoTtsChunks(fullText)
    if (chunks.isEmpty()) return emptyList()

    val chunkFiles = mutableListOf<String>()

    for ((idx, chunkText) in chunks.withIndex()) {
        // Generation check BEFORE each synthesis: if stopAudio() bumped the
        // counter, the entire sequence is stale - bail immediately.
        if (ttsGeneration != myTtsGeneration) return chunkFiles

        // Clean the chunk text (strip markdown that Kokoro reads literally).
        val cleaned = cleanTextForTts(chunkText)
        if (cleaned.isBlank()) continue

        // Sequential synthesis: one chunk at a time, via the existing
        // single-text synthesizeWithGateway (tracks currentCall for Stop).
        val audioBytes = synthesizeWithGateway(cleaned, persona)
        if (audioBytes == null || audioBytes.isEmpty()) continue

        // Generation check AFTER synthesis (it may have taken seconds -
        // Stop could have fired while we were waiting for Kokoro).
        if (ttsGeneration != myTtsGeneration) return chunkFiles

        // Write chunk to a transient file for the MediaPlayer
        val chunkFile = File(cacheDir, "tts_chunk_${myTtsGeneration}_${idx}.wav")
        chunkFile.writeBytes(audioBytes)
        chunkFiles.add(chunkFile.absolutePath)

        if (idx == 0) {
            // First chunk: enter SPEAKING and start the player.
            // Only enter SPEAKING once for the entire sequence.
            if (!requestAssistantFocus()) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return chunkFiles
            }
            _state.value = AssistantState.SPEAKING
            updateNotification("Speaking (streaming TTS)...")

            // Duration scale: full text vs first chunk text. Used to estimate
            // the total audio duration from chunk 0's actual MediaPlayer
            // duration, so the word-by-word sync in the HUD covers the FULL
            // response (not just chunk 0's few seconds).
            val firstChunkLen = cleaned.length.coerceAtLeast(1)
            val fullTextLen = fullText.length.coerceAtLeast(firstChunkLen)
            val durationScale = fullTextLen.toFloat() / firstChunkLen

            startChunkPlayback(chunkFile, persona, myTtsGeneration, chunks.size, durationScale)
        }
        // Subsequent chunks: just write the file. The onCompletion callback
        // from the previous chunk picks it up via the file naming convention.
    }

    return chunkFiles
}

/** Starts chunk playback - called for chunk 0 and re-invoked per chunk via onCompletion. */
private fun AssistantService.startChunkPlayback(
    firstChunkFile: File,
    persona: Persona,
    myTtsGeneration: Long,
    totalChunks: Int,
    durationScale: Float
) {
    val player = MediaPlayer()
    try {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (earpieceMode.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(firstChunkFile.absolutePath)

        var currentChunkIdx = 0

        player.setOnCompletionListener { mp ->
            if (currentPlayer !== mp) {
                mp.release()
                return@setOnCompletionListener
            }
            // Generation check: if stopAudio() bumped the counter, the entire
            // sequence is stale - clean up and do NOT play the next chunk.
            if (ttsGeneration != myTtsGeneration) {
                mp.release()
                currentPlayer = null
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                _voiceDuration.value = 0
                abandonAssistantFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
                return@setOnCompletionListener
            }

            currentChunkIdx++

            if (currentChunkIdx >= totalChunks) {
                // Last chunk finished - full cleanup, exactly like the classic
                // playAudioFile() onCompletion.
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                mp.release()
                currentPlayer = null
                _voiceDuration.value = 0
                abandonAssistantFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
                return@setOnCompletionListener
            }

            // More chunks: swap the data source on the SAME player.
            val nextFile = File(cacheDir, "tts_chunk_${myTtsGeneration}_${currentChunkIdx}.wav")
            if (nextFile.exists()) {
                try {
                    mp.reset()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(if (earpieceMode.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(nextFile.absolutePath)
                    mp.prepareAsync()
                } catch (e: Exception) {
                    android.util.Log.e("AssistantService", "Chunk playback error: ${e.message}")
                    mp.release()
                    currentPlayer = null
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                    abandonAssistantFocus()
                }
            } else {
                // Next chunk not yet written - synthesis still in flight. Poll.
                mainHandler.postDelayed({
                    if (ttsGeneration != myTtsGeneration || currentPlayer !== mp) {
                        mp.release()
                        if (currentPlayer === mp) currentPlayer = null
                        return@postDelayed
                    }
                    if (nextFile.exists()) {
                        try {
                            mp.reset()
                            mp.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(if (earpieceMode.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            mp.setDataSource(nextFile.absolutePath)
                            mp.prepareAsync()
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantService", "Chunk retry error: ${e.message}")
                            mp.release()
                            currentPlayer = null
                            _state.value = AssistantState.IDLE
                            updateNotification("Ready to help")
                            abandonAssistantFocus()
                        }
                    } else {
                        mainHandler.postDelayed({
                            if (ttsGeneration != myTtsGeneration || currentPlayer !== mp) {
                                mp.release()
                                if (currentPlayer === mp) currentPlayer = null
                                return@postDelayed
                            }
                            if (nextFile.exists()) {
                                try {
                                    mp.reset()
                                    mp.setAudioAttributes(
                                        AudioAttributes.Builder()
                                            .setUsage(if (earpieceMode.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build()
                                    )
                                    mp.setDataSource(nextFile.absolutePath)
                                    mp.prepareAsync()
                                } catch (e: Exception) {
                                    android.util.Log.e("AssistantService", "Chunk retry-2 error: ${e.message}")
                                    mp.release()
                                    currentPlayer = null
                                    _state.value = AssistantState.IDLE
                                    updateNotification("Ready to help")
                                    abandonAssistantFocus()
                                }
                            } else {
                                _state.value = AssistantState.IDLE
                                updateNotification("Ready to help")
                                mp.release()
                                currentPlayer = null
                                abandonAssistantFocus()
                            }
                        }, 200)
                    }
                }, 200)
            }
        }

        player.setOnPreparedListener { mp ->
            // Estimated full duration = chunk 0's actual audio duration scaled
            // by the text-length ratio (full text / first chunk). Gives the
            // word-by-word sync a duration covering the entire response.
            _voiceDuration.value = (mp.duration * durationScale).toInt()
            mp.start()
        }

        player.setOnErrorListener { mp, what, extra ->
            android.util.Log.e("AssistantService", "playChunkedTtsGateway: MediaPlayer error what=$what extra=$extra")
            if (currentPlayer === mp) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                mp.release()
                currentPlayer = null
                _voiceDuration.value = 0
                abandonAssistantFocus()
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            } else {
                mp.release()
            }
            true
        }

        currentPlayer = player
        player.prepareAsync()
    } catch (e: Exception) {
        if (currentPlayer === player) currentPlayer = null
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        player.release()
        abandonAssistantFocus()
    }
}

fun AssistantService.replayMessageAudio(message: ChatMessage, persona: Persona) {
    if (message.audioFilePath != null && File(message.audioFilePath).exists()) {
        playAudioFile(File(message.audioFilePath))
    } else {
        // The stored text is the original markdown (kept for the chat bubble) — clean
        // it off-Main, exactly like the live response flows, before speaking.
        serviceScope.launch(Dispatchers.IO) {
            val cleaned = cleanTextForTts(message.text)
            withContext(Dispatchers.Main) { playResponse(persona, deviceText = cleaned) }
        }
    }
}
