package com.nikosm.voiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VADAudioRecorder(
    private val detector: VADDetector,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: (File) -> Unit
) {
    private var job: Job? = null
    
    private val sampleRate = 16000
    private val chunkSize = 512
    
    private var isMonitoring = false
    private var isRecording = false
    private var speechFrames = 0
    private var silenceFrames = 0
    
    private val speechThreshold = 3 // ~100ms
    private val silenceThreshold = 60 // ~2.0s
    private val probThreshold = 0.5f
    
    private val recordedData = mutableListOf<ShortArray>()

    // Guards recordedData: the recording loop mutates it on Dispatchers.IO while stop()
    // (Main thread) can clear it concurrently. Unsynchronized add/clear interleaving can
    // tear the list state, and stop() landing mid-saveToWav() made its iterator throw
    // ConcurrentModificationException — silently caught, killing the recording coroutine,
    // dropping the finished utterance and leaving hands-free VAD dead until re-toggled.
    // Held only for microsecond-scale in-memory ops (add/clear/snapshot) — never file
    // I/O, never across a suspension — so Main's stop() never blocks for long and no
    // deadlock cycle exists with VADDetector's own internal state lock.
    private val dataLock = Any()

    
    var muted = false
    var isPaused = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        isPaused = false
        job = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize.coerceAtLeast(chunkSize * 2)
            )
            
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                isMonitoring = false
                Log.e("VADAudioRecorder", "AudioRecord initialization failed")
                return@launch
            }
            
            try {
                audioRecord.startRecording()
                val buffer = ShortArray(chunkSize)
                
                Log.d("VADAudioRecorder", "VAD monitoring started")
                
                while (isActive && isMonitoring) {
                    if (isPaused || muted) {
                        if (isRecording) {
                            isRecording = false
                            synchronized(dataLock) { recordedData.clear() }
                            detector.reset()
                        }
                        delay(200)
                        continue
                    }
                    
                    val read = audioRecord.read(buffer, 0, chunkSize)
                    if (read != chunkSize) {
                        Log.w("VADAudioRecorder", "Unexpected read size: $read (expected $chunkSize)")
                    }
                    if (read == chunkSize) {
                        val floatData = FloatArray(chunkSize) { buffer[it] / 32768.0f }
                        val prob = detector.isSpeech(floatData)
                        
                        if (prob > probThreshold) {
                            speechFrames++
                            silenceFrames = 0
                        } else {
                            silenceFrames++
                            speechFrames = 0
                        }
                        
                        if (!isRecording && speechFrames >= speechThreshold) {
                            isRecording = true
                            withContext(Dispatchers.Main) { onSpeechStart() }
                            synchronized(dataLock) { recordedData.clear() }
                        }
                        
                        if (isRecording) {
                            synchronized(dataLock) { recordedData.add(buffer.copyOf()) }
                            if (silenceFrames >= silenceThreshold) {
                                isRecording = false
                                val file = saveToWav()
                                isPaused = true // Pause until resumed by service (e.g. after response)
                                withContext(Dispatchers.Main) { onSpeechEnd(file) }
                                synchronized(dataLock) { recordedData.clear() }
                                detector.reset()
                                speechFrames = 0
                                silenceFrames = 0
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VADAudioRecorder", "Recording error: ${e.message}")
            } finally {
                try { audioRecord.stop() } catch (e: Exception) {}
                audioRecord.release()
                isMonitoring = false
            }
        }
    }
    
    fun stop() {
        isMonitoring = false
        job?.cancel()
        job = null
        isRecording = false
        // Runs on Main while the IO loop may be mid-iteration; the lock makes this clear
        // atomic against add() and saveToWav()'s snapshot (the CME source). The loop can
        // still append one orphan chunk if it already passed its flag check — harmless,
        // the next start()/speech-start clears it.
        synchronized(dataLock) { recordedData.clear() }
        detector.reset()
        Log.d("VADAudioRecorder", "VAD monitoring stopped")
    }

    fun resume() {
        isPaused = false
        detector.reset()
        speechFrames = 0
        silenceFrames = 0
        Log.d("VADAudioRecorder", "VAD Monitoring Resumed")
    }
    
    private fun saveToWav(): File {
        // Snapshot the chunk list under the lock (a cheap reference copy), then release
        // the lock BEFORE the file I/O: stop() must never block for the write duration,
        // and iterating the live list let stop()'s clear() throw
        // ConcurrentModificationException mid-save. The header size derives from the same
        // snapshot, keeping header and samples consistent.
        val chunks = synchronized(dataLock) { ArrayList(recordedData) }
        val file = File(cacheDir, "vad_recording_${System.currentTimeMillis()}.wav")
        val dataSize = chunks.size * chunkSize * 2

        FileOutputStream(file).use { fos ->
            writeWavHeader(fos, dataSize)
            val byteBuffer = ByteBuffer.allocate(chunkSize * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in chunks) {
                byteBuffer.clear()
                for (s in chunk) byteBuffer.putShort(s)
                fos.write(byteBuffer.array())
            }
        }
        return file
    }
    
    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // Mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // Byte rate
        header.putShort(2.toShort()) // Block align
        header.putShort(16.toShort()) // Bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        fos.write(header.array())
    }
}
