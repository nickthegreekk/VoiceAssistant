package com.nikosm.voiceassistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

class VADDetector(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    private var state: FloatArray = FloatArray(2 * 1 * 128)

    // Guards the recurrent `state` array: isSpeech() runs on the recorder's IO thread
    // while reset() runs on Main (resume()/stop()) — an unsynchronized fill-vs-read/write
    // could tear the state tensor mid-inference, and a post-run write-back could
    // resurrect pre-reset state. Kept separate from the recorder's data lock on purpose:
    // sharing it would hold the data lock across ONNX inference. synchronized is
    // reentrant, so the recorder's own IO-loop reset() calls never self-deadlock; the
    // only cross-thread cost is a coinciding Main-side reset() briefly waiting (~1ms).
    private val stateLock = Any()


    init {
        try {
            val modelBytes = context.assets.open("silero_vad.onnx").readBytes()
            session = env.createSession(modelBytes)
            Log.d("VADDetector", "Silero VAD model loaded successfully")
            
            // Re-adding diagnostics to see exactly what this specific file wants
            session?.inputInfo?.forEach { (name, info) ->
                Log.d("VADDetector", "Input '$name' info: ${info.info}")
            }
            session?.outputInfo?.forEach { (name, info) ->
                Log.d("VADDetector", "Output '$name' info: ${info.info}")
            }
        } catch (e: Exception) {
            Log.e("VADDetector", "Failed to load Silero VAD model: ${e.message}")

        }
    }

    fun isSpeech(samples: FloatArray): Float = synchronized(stateLock) {
        val s = session ?: return 0f

        val container = mutableMapOf<String, OnnxTensor>()
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong()))
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf())

        container["input"] = inputTensor
        container["state"] = stateTensor
        container["sr"] = srTensor

        return try {
            val result = s.run(container)

            @Suppress("UNCHECKED_CAST")
            val outputObj = result.get("output").orElse(null)
            @Suppress("UNCHECKED_CAST")
            val stateNObj = result.get("stateN").orElse(null)
            
            if (outputObj == null || stateNObj == null) return 0f

            val rawOutput = (outputObj.value as Array<FloatArray>)[0][0]
            
            // If the model returns a value outside [0, 1], it's likely a logit and needs sigmoid.
            val prob = if (rawOutput < 0f || rawOutput > 1f) {
                1.0f / (1.0f + kotlin.math.exp(-rawOutput.toDouble()).toFloat())
            } else {
                rawOutput
            }

            val newState = stateNObj.value as Array<Array<FloatArray>>
            var idx = 0
            for (i in 0 until 2) {
                for (k in 0 until 128) {
                    state[idx] = newState[i][0][k]
                    idx++
                }
            }

            result.close()
            prob
        } catch (e: Exception) {
            Log.e("VADDetector", "Inference failed: ${e.message}")
            0f
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    fun reset() {
        synchronized(stateLock) { state.fill(0f) }
    }

    fun close() {
        // Also under stateLock so the session can't be closed mid-inference (e.g.
        // onDestroy's close() racing the loop's last isSpeech() after stop()).
        synchronized(stateLock) {
            session?.close()
            env.close()
        }
    }
}