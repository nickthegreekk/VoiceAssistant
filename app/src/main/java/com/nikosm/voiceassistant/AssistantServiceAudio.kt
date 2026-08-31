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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        override fun onDone(utteranceId: String?) {
            serviceScope.launch(Dispatchers.Main) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                audioFocusRequest?.let { req -> audioManager.abandonAudioFocusRequest(req) }
                audioFocusRequest = null
                audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            }
        }
        override fun onError(utteranceId: String?) {
            onDone(utteranceId)
        }
    })
    
    tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "assistant_msg")
}

internal fun AssistantService.speakWithEspeak(text: String, persona: Persona) {
    stopAudio()
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
        engine.setVoice(voiceName)
        val samples = engine.synthesize(text)
        
        withContext(Dispatchers.Main) {
            if (silenced.value) {
                _state.value = AssistantState.IDLE
                updateNotification("Ready to help")
                return@withContext
            }

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
            
            withContext(Dispatchers.IO) {
                audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            }
            
            val durationMs = (samples.size.toFloat() / sampleRate * 1000).toInt()
            _voiceDuration.value = durationMs

            delay(durationMs.toLong() + 200)

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
