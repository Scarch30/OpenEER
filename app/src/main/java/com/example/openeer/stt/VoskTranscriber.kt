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

    /** Taux d’échantillonnage attendu (AudioRecord + Vosk) */
    const val SAMPLE_RATE = 16_000

    /** Dossier du modèle dans les assets et dans filesDir (même nom pour simplicité) */
    private const val ASSET_MODEL_DIR = "vosk-fr"
    private const val LOCAL_MODEL_DIR = "vosk-fr"
    private const val SENTINEL = ".ok"

    private val cachedModel = AtomicReference<Model?>()

    @Synchronized
    private fun ensureModel(ctx: Context): Model {
        cachedModel.get()?.let { return it }

        // Copie paresseuse des assets vers le stockage local de l’app
        val local = File(ctx.filesDir, LOCAL_MODEL_DIR)
        val sentinel = File(local, SENTINEL)
        if (!sentinel.exists()) {
            copyAssetDir(ctx, ASSET_MODEL_DIR, local)
            sentinel.parentFile?.mkdirs()
            sentinel.writeText("ok")
        }

        // Chargement du modèle
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
            return extractText(rec.finalResult) // final -> clé "text"
        }
    }

    /** Démarre une session de streaming (ShortArray PCM16 mono à 16 kHz) */
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
         * Envoie un buffer PCM16 (little-endian) à Vosk.
         * On convertit ShortArray -> ByteArray 16-bit LE.
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

        /** Retourne le texte partiel courant (clé JSON "partial") */
        fun partial(): String = runBlocking {
            mutex.withLock { extractPartial(recognizer.partialResult) }
        }

        /** Termine la reco et retourne le texte final (clé JSON "text") */
        fun finish(): String = runBlocking {
            mutex.withLock {
                val text = extractText(recognizer.finalResult)
                recognizer.close()
                text
            }
        }
    }

    // ---- Helpers JSON ----

    /** Pour les résultats finaux (Recognizer.finalResult) -> clé "text" */
    private fun extractText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString("text", "")
        } catch (t: Throwable) {
            Log.e(TAG, "JSON parse failed: ${t.message}")
            ""
        }
    }

    /** Pour les résultats partiels (Recognizer.partialResult) -> clé "partial" */
    private fun extractPartial(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString("partial", "")
        } catch (t: Throwable) {
            Log.e(TAG, "JSON parse failed: ${t.message}")
            ""
        }
    }

    // ---- Copie récursive d’un répertoire d’assets ----
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
