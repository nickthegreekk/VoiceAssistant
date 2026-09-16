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
import kotlinx.coroutines.delay
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
    // Fix #1b: System TTS is the ONE playback entry point that never routes through
    // stopAudio() — this function requests audio focus itself (below), and no live flow calls
    // a teardown beforehand. The per-sequence Stage-2 state therefore has to be cleared here,
    // or an unrelated earlier turn's values satisfy the UI's karaoke gate during THIS
    // playback (`SPEAKING && ttsPlaybackFraction != null && voiceDuration > 0`): the chunked
    // pipeline deliberately leaves `_ttsPlaybackFraction` at 1f when a Gateway turn ends, and
    // `_voiceDuration` keeps an earlier eSpeak turn's length, so the new response would be
    // pinned to full text (reveal suppressed) with a nonsense highlight painted from the old
    // fraction. Cleared before the no-play bail: a muted/unready engine must land clean too.
    _ttsPlaybackFraction.value = null
    _ttsWordTimestamps.value = null
    _voiceDuration.value = 0
    if (!ttsReady || silenced.value) {
        // No-play bail: release any focus held for this turn (stopRecording()
        // retains it through THINKING when the turn was expected to speak).
        // abandonAssistantFocus() no-ops when nothing is held.
        abandonAssistantFocus()
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    
    if (!requestAssistantFocus()) {
        abandonAssistantFocus()
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
    // abandonFocus=false: a new playback starts right after this teardown and
    // re-requests focus — keep the (possibly retained-through-THINKING) request
    // held so other apps stay ducked continuously (see stopAudio()).
    stopAudio(abandonFocus = false)
    // C4: check silence BEFORE any synthesis work — when silent mode is on, skip the
    // entire pipeline (engine initialization, voice selection, setVoice, synthesize)
    // since the result would be discarded anyway. Same synchronous early-bail pattern
    // as speakTextOnDevice's silenced check.
    if (silenced.value) {
        // No-play bail: release any focus held for this turn (see speakTextOnDevice).
        abandonAssistantFocus()
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    val engine = espeakEngine ?: run {
        // No-play bail: release any focus held for this turn.
        abandonAssistantFocus()
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
                abandonAssistantFocus()
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return@withContext
            }
            _state.value = AssistantState.SPEAKING
            updateNotification("Speaking (eSpeak)...")

            val sampleRate = engine.getSampleRate()
            if (sampleRate <= 0 || samples.isEmpty()) {
                abandonAssistantFocus()
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
                abandonAssistantFocus()
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return@withContext
            }

            currentAudioTrack = audioTrack
            audioTrack.play()

            // Set the voice duration UP FRONT (before the blocking write below) so
            // the UI reveal tickers (classic box + HUD WordTimedText) have a target
            // to animate against from the first frame. Previously this was set only
            // AFTER the blocking write completed — i.e. after playback finished —
            // which is why the classic box dumped the full text at once: the
            // fallback ticker saw durationMs <= 0 and pinned the reveal to 1f.
            val durationMs = (samples.size.toFloat() / sampleRate * 1000).toInt()
            _voiceDuration.value = durationMs

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
        }
    }
}

internal fun AssistantService.playAudioFile(file: File) {
    // abandonFocus=false: a new playback starts right after this teardown and
    // re-requests focus — keep the (possibly retained-through-THINKING) request
    // held so other apps stay ducked continuously (see stopAudio()).
    stopAudio(abandonFocus = false)
    if (silenced.value) {
        // No-play bail: release any focus held for this turn (see speakTextOnDevice).
        abandonAssistantFocus()
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    if (!requestAssistantFocus()) {
        abandonAssistantFocus()
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

fun AssistantService.stopAudio(abandonFocus: Boolean = true) {
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
    // Stage-2 fraction/timestamps are PER-SEQUENCE state, not per-session state, so
    // they belong in this teardown next to _voiceDuration. Without this, a value left
    // over from an earlier Gateway (chunked) turn survived into an unrelated later
    // playback in the same session: the UI's karaoke gate is
    // `SPEAKING && ttsPlaybackFraction != null && voiceDuration > 0`, so a stale
    // fraction could (a) wrongly suppress the typewriter reveal of a device-voice
    // (eSpeak/System TTS) response and (b) paint a nonsense estimated word highlight
    // from a fraction that belongs to a response that finished playing long ago.
    // Redundant-but-harmless for the chunked path: playChunkedTtsGateway() nulls both
    // at sequence start anyway, and this teardown has just bumped ttsGeneration, so
    // any sequence that was mid-flight is dead and cannot republish.
    _ttsPlaybackFraction.value = null
    _ttsWordTimestamps.value = null
    // abandonFocus=false: the play-entry callers (speakWithEspeak / playAudioFile)
    // tear down any previous playback here and then IMMEDIATELY re-request focus.
    // Abandoning first would bounce the focus the stopRecording() retention kept
    // through THINKING — other apps get a GAIN dispatch for a few milliseconds
    // and are re-ducked instantly (an audible-environment flicker even if brief).
    // With the flag false, the immediate re-request reuses the SAME held
    // AudioFocusRequest (same usage): the framework dispatches no focus change
    // to other apps for a re-request by the current holder, so ducking stays
    // continuous. If the usage changed (earpiece toggle), requestAssistantFocus()
    // abandons the superseded request itself — no leak either way.
    if (abandonFocus) {
        abandonAssistantFocus()
    }
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
/**
 * Parses the duration (seconds) of a WAV file from its raw bytes by reading
 * the canonical 44-byte RIFF header: sample rate at offset 24, data-subchunk
 * size at offset 40, channels at offset 22, bits-per-sample at offset 34.
 * Duration = dataSize / (sampleRate * bytesPerSample). Falls back to the
 * header's byteRate (offset 28) when present. Returns 0.0 for anything that
 * isn't a standard WAV.
 *
 * Used to keep the accumulated word-timestamp offset on the same clock as
 * the MediaPlayer durations (mp.duration) that drive ttsPlaybackFraction.
 */
internal fun wavDurationSeconds(wavBytes: ByteArray): Double {
    if (wavBytes.size < 44) return 0.0
    // "RIFF" magic
    if (wavBytes[0] != 0x52.toByte() || wavBytes[1] != 0x49.toByte()) return 0.0
    if (wavBytes[2] != 0x46.toByte() || wavBytes[3] != 0x46.toByte()) return 0.0
    // "WAVE" magic
    if (wavBytes[8] != 0x57.toByte() || wavBytes[9] != 0x41.toByte()) return 0.0
    if (wavBytes[10] != 0x56.toByte() || wavBytes[11] != 0x45.toByte()) return 0.0
    var sampleRate = 0
    for (i in 24 until 28) {
        sampleRate = sampleRate or ((wavBytes[i].toInt() and 0xFF) shl (8 * (i - 24)))
    }
    if (sampleRate <= 0) return 0.0
    var byteRate = 0L
    for (i in 28 until 32) {
        byteRate = byteRate or ((wavBytes[i].toInt() and 0xFF).toLong() shl (8 * (i - 28)))
    }
    var dataSizeLong = 0L
    for (i in 40 until 44) {
        dataSizeLong = dataSizeLong or (((wavBytes[i].toInt() and 0xFF).toLong()) shl (8 * (i - 40)))
    }
    if (dataSizeLong <= 0) return 0.0
    // dataSize is in BYTES. Prefer the header's byteRate (bytes/sec); fall back
    // to channels*bitsPerSample/8 when byteRate is absent.
    val bytesPerSec = if (byteRate > 0) byteRate.toDouble()
        else 1.0 * (wavBytes[22].toInt() and 0xFF) * ((wavBytes[34].toInt() and 0xFF) or ((wavBytes[35].toInt() and 0xFF) shl 8)) / 8.0
    if (bytesPerSec <= 0) return 0.0
    return dataSizeLong.toDouble() / bytesPerSec
}

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
            // No-play bail: release any focus held for this turn (stopRecording()
            // abandons immediately for NONE personas, so this is normally a
            // no-op kept for uniformity of the no-play landing contract).
            abandonAssistantFocus()
            _state.value = AssistantState.IDLE
            updateNotification("Ready to help")
        }
        VoiceMode.SYSTEM_TTS -> {
            if (deviceText != null) speakTextOnDevice(deviceText)
            else {
                if (file != null) playAudioFile(file)
                else {
                    abandonAssistantFocus()
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
                    abandonAssistantFocus()
                    _state.value = AssistantState.IDLE
                    updateNotification("Ready to help")
                }
            }
        }
        VoiceMode.GATEWAY -> {
            if (file != null) playAudioFile(file)
            else {
                if (deviceText != null) {
                    // No persisted audio for this text (streaming/chunked Gateway turns
                    // synthesize per chunk and never write a .wav). Re-synthesize through
                    // the Gateway instead of silently substituting System TTS, which would
                    // misrepresent the persona's configured voice. deviceText arrives
                    // pre-cleaned; the chunked pipeline re-cleans per chunk anyway.
                    synthesizeGatewayTextForPlayback(deviceText, persona) {
                        _state.value = AssistantState.IDLE
                        updateNotification("Voice unavailable - check TTS server")
                    }
                } else {
                    abandonAssistantFocus()
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
    if (chunks.isEmpty()) {
        // No-play bail: the sequence cannot start — release any focus held for
        // this turn (stopRecording() retained it through THINKING) and leave
        // THINKING (the flow's finally would otherwise skip its abandon because
        // playback was handed off).
        abandonAssistantFocus()
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return emptyList()
    }

    // Fresh-sequence state reset: the previous response's stale fraction (1f)
    // and timestamps must not leak into this turn's UI window before the first
    // chunk republishes them — a stale 1f fraction would misplace the karaoke
    // highlight (or pin it pre-speech), and stale timestamps could mis-align
    // against the new text.
    _ttsPlaybackFraction.value = null
    _ttsWordTimestamps.value = null

    val chunkFiles = mutableListOf<String>()
    var accumulatedTimestamps = mutableListOf<Map<String, Any>>()  // [{word, start, end}] absolute
    var cumulativeAudioS = 0.0  // full AUDIO duration of prior chunks (matches mp.duration timeline)

    for ((idx, chunkText) in chunks.withIndex()) {
        // Generation check BEFORE each synthesis: if stopAudio() bumped the
        // counter, the entire sequence is stale - bail immediately.
        if (ttsGeneration != myTtsGeneration) return chunkFiles

        // Clean the chunk text (strip markdown that Kokoro reads literally).
        val cleaned = cleanTextForTts(chunkText)
        if (cleaned.isBlank()) continue

        // Sequential synthesis: one chunk at a time, via the existing
        // single-text synthesizeWithGateway (tracks currentCall for Stop).
        val (audioBytes, tsJson) = synthesizeWithGateway(cleaned, persona)
        if (audioBytes == null || audioBytes.isEmpty()) continue

        // Parse and adjust word timestamps: Kokoro returns them relative to
        // the chunk start; add the cumulative AUDIO duration offset for
        // absolute position. The offset is the full WAV duration of prior
        // chunks (parsed from the RIFF header) — NOT the prior chunk's last
        // word .end, which excludes Kokoro's trailing pad (~0.1s per chunk)
        // and would drift the reveal progressively early across chunks.
        // Using full audio durations keeps the timestamp timeline identical
        // to the ttsPlaybackFraction timeline (which is built from each
        // chunk's actual mp.duration), so word positions and playback
        // position stay on the same clock.
        if (tsJson != null) {
            try {
                val chunkTimestamps = org.json.JSONArray(tsJson)
                for (i in 0 until chunkTimestamps.length()) {
                    val t = chunkTimestamps.getJSONObject(i)
                    accumulatedTimestamps.add(mapOf(
                        "word" to t.optString("word", ""),
                        "start" to (t.optDouble("start", 0.0) + cumulativeAudioS),
                        "end" to (t.optDouble("end", 0.0) + cumulativeAudioS)
                    ))
                }
                // Publish the accumulated timestamps to the service StateFlow
                _ttsWordTimestamps.value = accumulatedTimestamps.joinToString(",", "[", "]") { m ->
                    """{"word":"${m["word"]}","start":${m["start"]},"end":${m["end"]}}"""
                }
            } catch (e: Exception) {
                android.util.Log.w("AssistantService", "Failed to parse word timestamps: ${e.message}")
            }
        }

        // Advance the audio clock by THIS chunk's full WAV duration so the
        // next chunk's words land at the correct absolute position.
        cumulativeAudioS += wavDurationSeconds(audioBytes)

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
                abandonAssistantFocus()
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
            val cleanedChunks = chunks.map { cleanTextForTts(it) }
            val fullTextLen = cleanedChunks.sumOf { it.length }.coerceAtLeast(1)

            startChunkPlayback(
                chunkFile, persona, myTtsGeneration, chunks.size,
                fullTextLen, cleanedChunks
            )
        }
        // Subsequent chunks: just write the file. The onCompletion callback
        // from the previous chunk picks it up via the file naming convention.
    }

    // No-play guard: the loop ended without ever starting a chunk (chunk 0
    // synthesis failed/empty, or every chunk cleaned to blank) — playback will
    // never start, so release the focus stopRecording() retained through
    // THINKING and land the turn. The early generation-stale returns above are
    // excluded: those run after Stop, whose stopEverything() already abandoned.
    if (chunkFiles.isEmpty()) {
        abandonAssistantFocus()
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
    }
    return chunkFiles
}

/** Starts chunk playback - called for chunk 0 and re-invoked per chunk via onCompletion. */
private fun AssistantService.startChunkPlayback(
    firstChunkFile: File,
    persona: Persona,
    myTtsGeneration: Long,
    totalChunks: Int,
    fullTextLen: Int,
    cleanedChunks: List<String>
) {
    val player = MediaPlayer()
    var pollJob: kotlinx.coroutines.Job? = null
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
                pollJob?.cancel()
                _ttsPlaybackFraction.value = null
                _ttsWordTimestamps.value = null
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
                pollJob?.cancel()
                _ttsPlaybackFraction.value = 1f
                _ttsWordTimestamps.value = null
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

        // Cumulative tracking for the playback fraction (progressively refined)
        var cumulativeDurationMs = 0
        var preparedTextLen = 0
        var totalEstimatedMs = 0
        player.setOnPreparedListener { mp ->
            val chunkTextLen = cleanedChunks.getOrElse(currentChunkIdx) { "" }.length.coerceAtLeast(1)
            val cumulativeBefore = cumulativeDurationMs
            cumulativeDurationMs += mp.duration
            preparedTextLen += chunkTextLen

            // Progressively refine the total-duration estimate.
            //
            // Long arithmetic is MANDATORY here. cumulativeDurationMs and
            // fullTextLen are both Int, so the product overflows 32-bit range
            // for long responses — e.g. 274,425 ms of prepared audio × 8,000
            // cleaned chars ≈ 2.2e9 > Int.MAX (2,147,483,647). The wrapped
            // value is NEGATIVE, which made `totalEstimatedMs <= 0`:
            //   • the polling loop below fell into its `else 0f` branch, so the
            //     fraction FROZE at exactly 0.000 for the whole remainder of the
            //     response (teleprompter dead, stuck where it was), and
            //   • `_voiceDuration.value = totalEstimatedMs` published a negative
            //     duration, so the UI's `voiceDuration > 0` karaoke gate went
            //     false and the moving word highlight disappeared
            // — both symptoms from one overflow, which is why they always
            // broke together. The response length threshold is ~5.6k chars
            // (overflow lands at ~50% of playback), so short/medium test
            // responses never crossed it and appeared to work. Clamp the Long
            // result back into Int range before publishing.
            val estimatedTotalMs = cumulativeDurationMs.toLong() * fullTextLen / preparedTextLen
            totalEstimatedMs = estimatedTotalMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

            // Publish the refined estimate on EVERY chunk prepare (was
            // first-chunk-only). The UI derives elapsed audio time as
            // fraction × voiceDuration, so this denominator must track the same
            // refining totalEstimatedMs the fraction is divided by — freezing
            // it at the chunk-0 extrapolation reintroduced reveal drift.
            _voiceDuration.value = totalEstimatedMs

            mp.start()

            // Start/restart the fraction polling coroutine
            pollJob?.cancel()
            pollJob = serviceScope.launch(Dispatchers.Main) {
                while (currentPlayer === player && ttsGeneration == myTtsGeneration
                    && assistantState.value == AssistantState.SPEAKING) {
                    val seqPos = cumulativeBefore + mp.currentPosition
                    val fraction = if (totalEstimatedMs > 0) {
                        (seqPos.toFloat() / totalEstimatedMs).coerceIn(0f, 1f)
                    } else 0f
                    _ttsPlaybackFraction.value = fraction
                    delay(100)
                }
                // Sequence done or interrupted: fully reveal
                if (ttsGeneration == myTtsGeneration) {
                    _ttsPlaybackFraction.value = 1f
                }
            }
        }

        player.setOnErrorListener { mp, what, extra ->
            android.util.Log.e("AssistantService", "playChunkedTtsGateway: MediaPlayer error what=$what extra=$extra")
            pollJob?.cancel()
            _ttsPlaybackFraction.value = null
            _ttsWordTimestamps.value = null
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

// Replay / deferred Gateway playback entry point: re-synthesizes the full text
// through the persona's configured Gateway voice, on demand.
//
// Background: the Stage-2 streaming pipeline (playChunkedTtsGateway) synthesizes
// one chunk at a time and NEVER persists a .wav to disk, so a streamed turn's
// ChatMessage.audioFilePath is null by design and there is no file to replay.
// Re-synthesizing on demand is exactly what was specified when the chunked
// pipeline was introduced. Previously this case fell through to System TTS,
// silently misrepresenting a Gateway persona's configured voice.
//
// Dispatched onto Main because the chunked pipeline owns a MediaPlayer; the
// network synthesis itself hops to IO inside synthesizeWithGateway.
internal fun AssistantService.synthesizeGatewayTextForPlayback(
    text: String,
    persona: Persona,
    onNoAudio: () -> Unit
) {
    serviceScope.launch(Dispatchers.Main) {
        // Same teardown the device-voice play entries (speakWithEspeak / playAudioFile)
        // do: stopAudio() bumps ttsGeneration so any in-flight chunk sequence halts
        // atomically and the previous player is released. abandonFocus=false keeps the
        // held focus request so other apps stay ducked continuously.
        stopAudio(abandonFocus = false)
        val myGeneration = ttsGeneration
        if (silenced.value) {
            // No-play bail: release any focus held for this turn.
            abandonAssistantFocus()
            _state.value = AssistantState.IDLE
            updateNotification("Ready to help")
            return@launch
        }
        // Visible feedback for the synthesis latency, matching a live turn — instead
        // of the silent gap the old System-TTS substitution used to hide.
        _state.value = AssistantState.THINKING
        updateNotification("Thinking...")
        // Reuses the existing chunked pipeline verbatim: sentence chunking, per-chunk
        // synthesis via synthesizeWithGateway, gapless playback, word timestamps.
        val played = playChunkedTtsGateway(text, persona, myGeneration)
        // Empty result with a still-current generation means nothing was synthesized —
        // server unreachable, auth failure, or an HTTP error. Each is already logged
        // with its status by synthesizeWithGateway and reflected in the server status
        // dot; this makes it visible at the point of use instead of silently switching
        // to a different voice. A stale generation means the user stopped it, which
        // stopEverything() has already reflected in the UI.
        if (played.isEmpty() && ttsGeneration == myGeneration) onNoAudio()
    }
}

fun AssistantService.replayMessageAudio(message: ChatMessage, persona: Persona) {
    val storedPath = message.audioFilePath
    if (storedPath != null && File(storedPath).exists()) {
        // Cloud-path pre-synthesis persisted the exact bytes that were played, so
        // replay them verbatim regardless of voice mode. The reveal is re-armed here
        // too: playAudioFile() publishes its own _voiceDuration from the file, and
        // without the request the UI's "already animated" cache would keep this
        // replay visually silent (text in full, no sync).
        requestRevealRestart(message)
        playAudioFile(File(storedPath))
        return
    }
    if (persona.voiceMode == VoiceMode.GATEWAY) {
        // Streamed/chunked Gateway turn: no persisted .wav exists (see
        // synthesizeGatewayTextForPlayback). Re-synthesize the stored text through the
        // persona's OWN Gateway voice. message.text is the original markdown, kept for
        // the chat bubble — the chunked pipeline cleans each chunk itself, exactly as
        // the live streaming flow does. Deliberately NO reveal request: the chunked
        // path owns the response with its karaoke highlight, and the UI's reveal gate
        // is bypassed while a fraction is live.
        synthesizeGatewayTextForPlayback(message.text, persona) {
            _state.value = AssistantState.IDLE
            updateNotification("Voice unavailable - check TTS server")
        }
        return
    }
    // Unchanged: device-voice personas (System TTS / bundled eSpeak) replay through
    // their own engine, cleaning the stored markdown off-Main first.
    serviceScope.launch(Dispatchers.IO) {
        val cleaned = cleanTextForTts(message.text)
        withContext(Dispatchers.Main) {
            // Fix #1 follow-up for this entry point: bundled eSpeak reaches
            // stopAudio() inside speakWithEspeak() (which now clears these), but
            // System TTS does NOT route through stopAudio() at all — speakTextOnDevice()
            // requests focus directly. A fraction/timestamps left over from an earlier
            // Gateway turn would therefore survive into a System-TTS replay and
            // satisfy the UI's karaoke gate (stale highlight / suppressed reveal).
            // The stale _voiceDuration matters here too: System TTS never publishes a
            // duration of its own, so a left-over one would be read by the reveal as
            // this replay's length. Cleared before playback so the reveal waits for a
            // genuinely fresh duration (eSpeak) or falls back to its heuristic (System
            // TTS) — never a value from the previous response.
            _ttsPlaybackFraction.value = null
            _ttsWordTimestamps.value = null
            _voiceDuration.value = 0
            // Fix #3: arm the timed reveal for this replay (must be emitted BEFORE
            // playback so the UI is already waiting when the engine publishes the
            // replay's duration).
            requestRevealRestart(message)
            playResponse(persona, deviceText = cleaned)
        }
    }
}

/**
 * Fix #3: publishes a RevealRestartRequest for [message] so the classic
 * duration-synced reveal runs again over the audio the replay is about to play.
 * Called only by the NON-chunked replay paths (device-voice engine, stored file):
 * the chunked Gateway path keeps its karaoke highlight instead.
 *
 * The message's index into _messages is resolved by identity first (the caller
 * passes the very instance the UI holds, which is the instance in the list) and by
 * role+text as a fallback. A message that cannot be located is not signalled at
 * all — the UI honors a request only when its index still addresses the last
 * assistant bubble, so a wrong/absent index would be ignored anyway.
 */
private fun AssistantService.requestRevealRestart(message: ChatMessage) {
    val list = _messages.value
    var index = list.indexOfFirst { it === message }
    if (index < 0) index = list.indexOfFirst { it.role == message.role && it.text == message.text }
    if (index < 0) return
    revealRestartSeq++
    _revealRestartRequest.value = RevealRestartRequest(token = revealRestartSeq, messageIndex = index)
}
