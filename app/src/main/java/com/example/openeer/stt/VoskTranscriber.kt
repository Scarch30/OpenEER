package com.example.openeer.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

object VoskTranscriber {

    private const val TAG = "Vosk"
    /** Sample rate (Hz) expected by both AudioRecord and Vosk recognizer */
    const val SAMPLE_RATE = 16_000
    private const val ASSET_MODEL_DIR = "vosk-fr"   // dossier sous app/src/main/assets/
    private const val LOCAL_MODEL_DIR = "vosk-fr"   // dossier sous filesDir
    private const val SENTINEL = ".ok"

    private val cachedModel = AtomicReference<Model?>()

    @Synchronized
    private fun ensureModel(ctx: Context): Model {
        cachedModel.get()?.let { return it }

        // 1) Copier assets/vosk-fr -> filesDir/vosk-fr (si besoin)
        val local = File(ctx.filesDir, LOCAL_MODEL_DIR)
        val sentinel = File(local, SENTINEL)
        if (!sentinel.exists()) {
            copyAssetDir(ctx, ASSET_MODEL_DIR, local)
            sentinel.parentFile?.mkdirs()
            sentinel.writeText("ok")
        }

        // 2) Charger le modèle
        val m = Model(local.absolutePath)
        cachedModel.set(m)
        Log.i(TAG, "Vosk model loaded from: ${local.absolutePath}")
        return m
    }

    /** Transcription d’un WAV (mono 16 kHz 16-bit) complet */
    fun transcribe(ctx: Context, wavFile: File): String {
        val model = ensureModel(ctx)
        val data = wavFile.readBytes()
        Recognizer(model, SAMPLE_RATE.toFloat()).use { rec ->
            rec.acceptWaveForm(data, data.size)
            return extractText(rec.finalResult)
        }
    }

    /** Session streaming (pour alimenter avec des ShortArray 16 kHz mono) */
    fun startStreaming(ctx: Context): StreamingSession {
        val model = ensureModel(ctx)
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        return StreamingSession(rec)
    }

    class StreamingSession internal constructor(
        private val recognizer: Recognizer
    ) {
        private val mutex = Mutex()

        /**
         * Feed a PCM16 little-endian mono buffer to Vosk. The number of bytes
         * passed to [Recognizer.acceptWaveForm] matches exactly the data length.
         */
        fun feed(pcm: ShortArray) {
            if (pcm.isEmpty()) return
            val byteCount = pcm.size * 2
            val bytes = ByteArray(byteCount)
            var j = 0
            for (s in pcm) {
                val v = s.toInt()
                bytes[j++] = (v and 0xff).toByte()
                bytes[j++] = ((v ushr 8) and 0xff).toByte()
            }
            runBlocking {
                mutex.withLock {
                    recognizer.acceptWaveForm(bytes, byteCount)
                }
            }
        }

        fun partial(): String = runBlocking {
            mutex.withLock { extractText(recognizer.partialResult) }
        }

        fun finish(): String = runBlocking {
            mutex.withLock {
                val text = extractText(recognizer.finalResult)
                recognizer.close()
                text
            }
        }
    }

    private fun extractText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try { JSONObject(json).optString("text", "") }
        catch (t: Throwable) {
            Log.e(TAG, "JSON parse failed: ${t.message}")
            ""
        }
    }

    // --- Copie récursive d’un répertoire d’assets ---
    private fun copyAssetDir(ctx: Context, assetDir: String, dstDir: File) {
        dstDir.mkdirs()
        val am = ctx.assets
        val list = am.list(assetDir) ?: emptyArray()
        for (name in list) {
            val relPath = if (assetDir.isEmpty()) name else "$assetDir/$name"
            val children = am.list(relPath)
            if (children.isNullOrEmpty()) {
                // fichier
                val outFile = File(dstDir, name)
                if (!outFile.exists() || outFile.length() == 0L) {
                    am.open(relPath).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } else {
                // dossier
                val subDir = File(dstDir, name)
                copyAssetDir(ctx, relPath, subDir)
            }
        }
    }
}
