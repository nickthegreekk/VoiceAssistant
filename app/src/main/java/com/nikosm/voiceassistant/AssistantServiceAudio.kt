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
    requestAssistantFocus()
}

internal fun AssistantService.requestAssistantFocus() {
    val isEarpiece = earpieceMode.value
    val usage = if (isEarpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
    val playbackAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()
    audioManager.requestAudioFocus(audioFocusRequest!!)
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
}

fun AssistantService.speakTextOnDevice(text: String) {
    if (!ttsReady || silenced.value) {
        _state.value = AssistantState.IDLE
        updateNotification("Ready to help")
        return
    }
    
    requestAssistantFocus()
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

            requestAssistantFocus()
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
    if (audioFocusRequest == null) requestAssistantFocus()
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
        player.prepare()
        _voiceDuration.value = player.duration
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
        currentPlayer = player
        val vol = if (silenced.value) 0f else 1f
        player.setVolume(vol, vol)
        player.start()
    } catch (e: Exception) {
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
