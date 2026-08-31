package com.nikosm.voiceassistant

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class EspeakEngine(private val context: Context) {
    private var sampleRate: Int = 0

    init {
        try {
            System.loadLibrary("voiceassistant")
            val dataDir = File(context.filesDir, "espeak-ng-data")
            if (!dataDir.exists()) {
                copyAssets("espeak-ng-data", context.filesDir)
            }
            sampleRate = nativeInit(context.filesDir.absolutePath)
            Log.d("EspeakEngine", "eSpeak NG initialized with sample rate: $sampleRate")
        } catch (e: Exception) {
            Log.e("EspeakEngine", "Failed to initialize eSpeak NG: ${e.message}")
        }
    }

    private fun copyAssets(assetDir: String, targetDir: File) {
        val assetManager = context.assets
        val assets = assetManager.list(assetDir) ?: return
        val destDir = File(targetDir, assetDir)
        destDir.mkdirs()

        for (asset in assets) {
            val fullAssetPath = "$assetDir/$asset"
            val subAssets = assetManager.list(fullAssetPath)
            if (subAssets.isNullOrEmpty()) {
                // It's a file
                val destFile = File(destDir, asset)
                assetManager.open(fullAssetPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory
                copyAssets(fullAssetPath, targetDir)
            }
        }
    }

    fun setVoice(voiceName: String): Boolean {
        return nativeSetVoice(voiceName) == 0
    }

    fun synthesize(text: String): ShortArray {
        return nativeSynthesize(text)
    }

    fun getSampleRate(): Int = sampleRate

    fun release() {
        nativeTerminate()
    }

    private external fun nativeInit(dataPath: String): Int
    private external fun nativeSetVoice(voiceName: String): Int
    private external fun nativeSynthesize(text: String): ShortArray
    private external fun nativeTerminate()
}
