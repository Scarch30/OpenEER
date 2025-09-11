package com.example.openeer.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

object VoskTranscriber {

    private const val TAG = "Vosk"
    private const val SAMPLE_RATE = 16000f
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
        Recognizer(model, SAMPLE_RATE).use { rec ->
            rec.acceptWaveForm(data, data.size)
            return extractText(rec.finalResult)
        }
    }

    /** Session streaming (pour alimenter avec des ShortArray 16 kHz mono) */
    fun startStreaming(ctx: Context): StreamingSession {
        val model = ensureModel(ctx)
        val rec = Recognizer(model, SAMPLE_RATE)
        return StreamingSession(rec)
    }

    class StreamingSession internal constructor(
        private val recognizer: Recognizer
    ) {
        fun feed(pcm: ShortArray) {
            if (pcm.isEmpty()) return
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            val bytes = bb.array()
            recognizer.acceptWaveForm(bytes, bytes.size)
        }

        fun partial(): String = extractText(recognizer.partialResult)

        fun finish(): String = extractText(recognizer.finalResult).also {
            recognizer.close()
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
