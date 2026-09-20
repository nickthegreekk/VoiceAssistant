package com.nikosm.voiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlin.concurrent.Volatile
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
    private val onSpeechEnd: (File) -> Unit,
    private val onAmplitudeUpdate: (Float) -> Unit = {}
) {
    private var job: Job? = null

    // VoV step 1 (standalone AEC wiring test): the native AcousticEchoCanceler attached
    // to the current AudioRecord's session, or null when unavailable / attach failed.
    // Created in start() on the IO loop, released in that loop's finally — same lifecycle
    // as the AudioRecord itself. @Volatile matches muted/isPaused: the attach happens on
    // one IO worker, the finally release may run on another after a suspension point.
    @Volatile
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    
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

    // M-pre-roll: ring buffer of the most recent raw chunks, captured continuously in
    // the idle/pre-detection phase. The trigger needs ~3 chunks (~100ms) of consistent
    // speech before isRecording flips — without this, the first two of those chunks
    // (the actual onset of a short word: "Hi", "Go") were physically discarded,
    // clipping short utterances enough to confuse STT. Capacity 3 (~96ms) covers the
    // trigger latency plus one jitter frame. On trigger the buffered chunks are
    // PREPENDED to recordedData (the capture happens after the live-append, so the
    // trigger chunk itself is never doubled), then the ring clears and live appending
    // continues. Shares dataLock with recordedData rather than a separate lock: both
    // buffers are "audio of the current utterance", they are mutated at exactly the
    // same points (trigger-prepend, speech-end, stop, mute/pause reset), and one lock
    // keeps a single consistent snapshot boundary — with the same rules as before
    // (tiny in-memory ops only, never held across suspension or file I/O).
    private val preRollChunks = ArrayDeque<ShortArray>()
    private val preRollCapacity = 3

    
    // M4-adjacent: written from Main (toggleMicMute / the state watcher's pause()),
    // read on the IO recording loop — @Volatile removes the visibility lag.
    @Volatile
    var muted = false

    @Volatile
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

            // VoV step 1: attach Android's native AcousticEchoCanceler to this
            // AudioRecord's session. Hardware/software AEC support varies per device,
            // so this is a pure availability + attach test: log the outcome and keep
            // going either way. The whole block is best-effort — an AEC problem must
            // never take VAD monitoring down, we simply continue without AEC.
            acousticEchoCanceler = try {
                val aecAvailable = AcousticEchoCanceler.isAvailable()
                Log.d("VADAudioRecorder", "AEC available: $aecAvailable")
                if (aecAvailable) {
                    val aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                    if (aec != null) {
                        val enableResult = aec.setEnabled(true)
                        Log.d(
                            "VADAudioRecorder",
                            "AEC attached to session ${audioRecord.audioSessionId}: " +
                                "setEnabled(true) result=$enableResult, enabled=${aec.enabled}"
                        )
                        aec
                    } else {
                        Log.w(
                            "VADAudioRecorder",
                            "AEC reported available but create() returned null — continuing without AEC"
                        )
                        null
                    }
                } else {
                    Log.d(
                        "VADAudioRecorder",
                        "AEC not available on this device — continuing without AEC"
                    )
                    null
                }
            } catch (e: Exception) {
                Log.w("VADAudioRecorder", "AEC attach failed (continuing without AEC): ${e.message}")
                null
            }
            
            try {
                audioRecord.startRecording()
                val buffer = ShortArray(chunkSize)
                
                Log.d("VADAudioRecorder", "VAD monitoring started")
                
                while (isActive && isMonitoring) {
                    if (isPaused || muted) {
                        if (isRecording) {
                            isRecording = false
                            synchronized(dataLock) {
                                recordedData.clear()
                                preRollChunks.clear()
                            }
                            detector.reset()
                        }
                        onAmplitudeUpdate(0f)
                        delay(200)
                        continue
                    }
                    
                    val read = audioRecord.read(buffer, 0, chunkSize)
                    if (read != chunkSize) {
                        Log.w("VADAudioRecorder", "Unexpected read size: $read (expected $chunkSize)")
                    }
                    if (read == chunkSize) {
                        // Max absolute amplitude for the visualizer
                        var maxAmp = 0
                        for (i in 0 until chunkSize) {
                            val abs = Math.abs(buffer[i].toInt())
                            if (abs > maxAmp) maxAmp = abs
                        }
                        onAmplitudeUpdate(maxAmp / 32767f)

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
                            synchronized(dataLock) {
                                // Prepend the pre-trigger chunks — they include the
                                // onset that actually caused the detection — ahead of
                                // the live append below, so nothing before isRecording
                                // is lost. Clearing the ring afterwards lets it refill
                                // with fresh audio for the next utterance.
                                recordedData.addAll(0, preRollChunks)
                                preRollChunks.clear()
                            }
                        }
                        
                        if (isRecording) {
                            synchronized(dataLock) { recordedData.add(buffer.copyOf()) }
                            if (silenceFrames >= silenceThreshold) {
                                isRecording = false
                                val file = saveToWav()
                                isPaused = true // Pause until resumed by service (e.g. after response)
                                withContext(Dispatchers.Main) { onSpeechEnd(file) }
                                synchronized(dataLock) {
                                    recordedData.clear()
                                    preRollChunks.clear()
                                }
                                detector.reset()
                                speechFrames = 0
                                silenceFrames = 0
                            }
                        }

                        // M-pre-roll capture: while not recording, continuously keep the
                        // most recent chunks (oldest evicted at capacity). Captured AFTER
                        // the append block above so the chunk that trips the threshold is
                        // appended live exactly once and never duplicated in the ring.
                        if (!isRecording) {
                            synchronized(dataLock) {
                                if (preRollChunks.size >= preRollCapacity) preRollChunks.removeFirst()
                                preRollChunks.addLast(buffer.copyOf())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VADAudioRecorder", "Recording error: ${e.message}")
            } finally {
                // VoV step 1: release the AEC together with the AudioRecord it is
                // attached to — the effect must be released before its audio session
                // goes away. Never fatal: release problems are logged, not thrown.
                try {
                    if (acousticEchoCanceler != null) {
                        acousticEchoCanceler?.release()
                        acousticEchoCanceler = null
                        Log.d("VADAudioRecorder", "AEC released")
                    }
                } catch (e: Exception) {
                    Log.w("VADAudioRecorder", "AEC release failed: ${e.message}")
                }
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
        onAmplitudeUpdate(0f)
        // Runs on Main while the IO loop may be mid-iteration; the lock makes this clear
        // atomic against add() and saveToWav()'s snapshot (the CME source). The loop can
        // still append one orphan chunk if it already passed its flag check — harmless,
        // the next start()/speech-start clears it.
        synchronized(dataLock) {
            recordedData.clear()
            preRollChunks.clear()
        }
        detector.reset()
        Log.d("VADAudioRecorder", "VAD monitoring stopped")
    }

    fun pause() {
        isPaused = true
        Log.d("VADAudioRecorder", "VAD Monitoring Paused")
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
