package com.nikosm.voiceassistant

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class EspeakEngine(private val context: Context) {
    private var sampleRate: Int = 0

    private companion object {
        const val DATA_DIR_NAME = "espeak-ng-data"
        // Staging name for the atomic copy in prepareDataDirectory(). Must never be
        // mistaken for real data: a tree only becomes live via a full rename onto
        // DATA_DIR_NAME, so an interrupted copy can never surface as a truncated
        // data directory (espeak_Initialize reacts to one with a native exit(1)
        // that kills the whole process and is uncatchable from Java).
        const val STAGING_DIR_NAME = "espeak-ng-data.tmp"
        // Written only after a staged copy was fully renamed into place. Its absence
        // marks a directory as untrusted (see prepareDataDirectory()).
        const val STAMP_FILE_NAME = "espeak-ng-data.stamp"
    }

    init {
        try {
            System.loadLibrary("voiceassistant")
            prepareDataDirectory()
            sampleRate = nativeInit(context.filesDir.absolutePath)
            // espeak_Initialize returns the sample rate on success and a negative
            // EE_* error code on failure. A non-positive rate must never be presented
            // as a working engine — nativeSynthesize would run against uninitialized
            // espeak-ng globals.
            check(sampleRate > 0) { "espeak_Initialize failed with code $sampleRate" }
            Log.d("EspeakEngine", "eSpeak NG initialized with sample rate: $sampleRate")
        } catch (e: Exception) {
            Log.e("EspeakEngine", "Failed to initialize eSpeak NG: ${e.message}")
            // Rethrow so the owning service's espeakEngine getter catches this and
            // keeps returning null: playback then takes its existing "engine
            // unavailable" bail-out instead of running against a dead engine.
            throw IllegalStateException("eSpeak NG engine is unusable", e)
        }
    }

    /**
     * Guarantees [DATA_DIR_NAME] holds a COMPLETE espeak-ng-data tree before
     * nativeInit runs. espeak_Initialize reacts to a truncated tree by calling
     * native exit(1), which kills the whole process from code Java cannot catch
     * (observed on device), so a partial tree must never exist under the real name.
     *
     * Atomic-copy pattern: the asset tree is staged into [STAGING_DIR_NAME] and only
     * renamed onto [DATA_DIR_NAME] after every file has been written and closed. A
     * force-quit / OS kill / ENOSPC mid-copy therefore leaves at most a leftover
     * staging directory — which is wiped and re-copied from scratch on the next
     * launch — never a truncated directory under the real name.
     *
     * The stamp file exists because trees written by the pre-atomic-copy version of
     * this code cannot be trusted either (a device was observed with a 4-file/564KB
     * remnant of the 439-file/12.8MB tree): a real directory without a stamp is
     * re-staged exactly like a missing one.
     */
    private fun prepareDataDirectory() {
        val dataDir = File(context.filesDir, DATA_DIR_NAME)
        val stagingDir = File(context.filesDir, STAGING_DIR_NAME)
        val stampFile = File(context.filesDir, STAMP_FILE_NAME)

        // Fast path: a stamped tree was fully published by an earlier launch. Any
        // leftover staging directory is debris from an interrupted copy — remove it
        // so repeated interruptions never accumulate wasted storage.
        if (dataDir.exists() && stampFile.exists()) {
            stagingDir.deleteRecursively()
            return
        }

        // Missing tree, legacy pre-stamp tree, or stamp lost: nothing here can be
        // trusted — wipe everything and start the copy over from scratch.
        stagingDir.deleteRecursively()
        dataDir.deleteRecursively()
        stampFile.delete()

        copyAssetTree(DATA_DIR_NAME, stagingDir)
        // Same-volume rename (both live under filesDir): atomic for process-death
        // purposes — readers see either no directory or the complete tree.
        if (!stagingDir.renameTo(dataDir)) {
            stagingDir.deleteRecursively()
            throw IOException("Could not publish staged espeak-ng-data directory")
        }
        stampFile.writeText("complete")
    }

    private fun copyAssetTree(assetPath: String, destDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return
        destDir.mkdirs()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDest = File(destDir, entry)
            val nested = assetManager.list(childAssetPath)
            if (nested.isNullOrEmpty()) {
                // It's a file
                assetManager.open(childAssetPath).use { input ->
                    FileOutputStream(childDest).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory
                copyAssetTree(childAssetPath, childDest)
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
