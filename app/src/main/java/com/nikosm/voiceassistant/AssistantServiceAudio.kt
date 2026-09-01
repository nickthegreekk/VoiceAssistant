package com.nikosm.voiceassistant

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// A3: monotonically increasing utterance counter — two calls can never share an ID
// (a time-based ID could collide for two calls in the same millisecond).
private var ttsUtteranceCounter: Long = 0

fun AssistantService.toggleEarpieceMode() {
    val before = earpieceMode.value
    _earpieceMode.value = !earpieceMode.value
    android.util.Log.d("AssistantService", "DEBUG: toggleEarpieceMode called — before=$before after=${_earpieceMode.value}")
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
                    val allDevices = audioManager.availableCommunicationDevices.map { it.type }
                    android.util.Log.d("AssistantService", "DEBUG: available communication devices = $allDevices")
                    val earpiece = audioManager.availableCommunicationDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpiece != null) {
                        val success = audioManager.setCommunicationDevice(earpiece)
                        android.util.Log.d("AssistantService", "DEBUG: setCommunicationDevice(earpiece) returned $success")
                    } else {
                        android.util.Log.d("AssistantService", "DEBUG: no earpiece device found in available list")
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
                audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
                audioFocusRequest = null
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            }
        }
        override fun onError(id: String?) {
            // A3: same ID gate as onDone — a stale error callback must not clean up.
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
        audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
        audioFocusRequest = null
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
                                audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
                                audioFocusRequest = null
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
                audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
                audioFocusRequest = null
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
                audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
                audioFocusRequest = null
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
        audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
        audioFocusRequest = null
    }
}

fun AssistantService.stopAudio() {
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
    audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
    audioFocusRequest = null
    audioManager.mode = AudioManager.MODE_NORMAL
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
}

fun AssistantService.stopEverything() {
    currentCall?.cancel()
    currentCall = null
    stopAudio()
    if (assistantState.value == AssistantState.THINKING || assistantState.value == AssistantState.LISTENING) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
    }
    _voiceDuration.value = 0
    audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
    audioFocusRequest = null
    audioManager.mode = AudioManager.MODE_NORMAL
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
}

fun AssistantService.playResponse(persona: Persona, file: File? = null, deviceText: String? = null) {
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

fun AssistantService.replayMessageAudio(message: ChatMessage, persona: Persona) {
    if (message.audioFilePath != null && File(message.audioFilePath).exists()) {
        playAudioFile(File(message.audioFilePath))
    } else {
        playResponse(persona, deviceText = message.text)
    }
}
