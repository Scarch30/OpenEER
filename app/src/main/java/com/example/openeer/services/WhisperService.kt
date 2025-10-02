package com.example.openeer.services

import android.content.Context
import android.util.Log
import com.example.openeer.media.decodeWaveFile
import com.whispercpp.java.whisper.WhisperSegment
import com.whispercpp.java.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * Façade minimale au-dessus de la lib Java com.whispercpp.java.whisper.*
 * - init/load du modèle depuis assets (différé -> Option A)
 * - transcription WAV -> texte
 * - transcription WAV -> segments (avec timecodes)
 * - release
 *
 * Important : on garde l’API attendue par le reste d’OpenEER.
 */
object WhisperService {
    private const val LOG_TAG = "WhisperService"

    // Contexte Whisper (thread-safe via l’executor interne de WhisperContext)
    @Volatile private var ctx: WhisperContext? = null

    // Empêche plusieurs chargements concurrents
    private val loadMutex = Mutex()

    /**
     * Vrai si le modèle est déjà chargé.
     */
    fun isLoaded(): Boolean = (ctx != null)

    /**
     * Utilitaire : si non chargé, le charge (idempotent).
     * Peut être appelé en arrière-plan après l’écran d’accueil (Option A).
     */
    suspend fun ensureLoaded(
        appCtx: Context,
        assetPath: String = "models/ggml-small-fr-q5_1.bin"
    ) {
        if (ctx != null) return
        loadModel(appCtx, assetPath)
    }

    /**
     * Charge le modèle depuis les assets s’il n’est pas déjà chargé.
     * Par défaut : models/ggml-small-fr-q5_1.bin (présent dans le projet).
     * Idempotent et thread-safe.
     */
    suspend fun loadModel(
        appCtx: Context,
        assetPath: String = "models/ggml-small-fr-q5_1.bin"
    ) = withContext(Dispatchers.Default) {
        if (ctx != null) return@withContext
        loadMutex.withLock {
            if (ctx != null) return@withLock
            try {
                Log.d(LOG_TAG, "Whisper system: ${WhisperContext.getSystemInfo()}")
            } catch (_: Throwable) { /* non-bloquant */ }

            Log.d(LOG_TAG, "Loading Whisper model from assets: $assetPath")
            val created = WhisperContext.createContextFromAsset(appCtx.assets, assetPath)
            ctx = created
            Log.d(LOG_TAG, "Whisper model loaded from assets: $assetPath")
        }
    }

    /**
     * Transcrit un fichier WAV (mono 16 kHz PCM 16-bit) et renvoie le texte concaténé.
     */
    suspend fun transcribeWav(wavFile: File): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        Log.d(LOG_TAG, "Decoding WAV: ${wavFile.absolutePath}")
        val startDecode = System.currentTimeMillis()
        val floats = decodeWaveFile(wavFile) // FloatArray normalisée [-1,1]
        val durDecode = System.currentTimeMillis() - startDecode
        Log.d(
            LOG_TAG,
            "WAV decoded: samples=${floats.size} sr=16000 durMs=${floats.size / 16} (decode=${durDecode}ms)"
        )
        try {
            Log.d(LOG_TAG, "Whisper transcribeData() start…")
            val t0 = System.currentTimeMillis()
            val text = c.transcribeData(floats)
            val t1 = System.currentTimeMillis()
            Log.d(LOG_TAG, "Whisper transcribeData() done in ${t1 - t0} ms")
            text
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /**
     * Transcrit un WAV et renvoie la liste de segments avec timecodes (unités Whisper).
     */
    suspend fun transcribeWavWithTimestamps(wavFile: File): List<WhisperSegment> =
        withContext(Dispatchers.Default) {
            val c = ensureCtx()
            Log.d(LOG_TAG, "Decoding WAV: ${wavFile.absolutePath}")
            val floats = decodeWaveFile(wavFile)
            Log.d(
                LOG_TAG,
                "WAV decoded: samples=${floats.size} sr=16000 durMs=${floats.size / 16}"
            )
            try {
                val t0 = System.currentTimeMillis()
                val segments = c.transcribeDataWithTime(floats)
                val t1 = System.currentTimeMillis()
                Log.d(
                    LOG_TAG,
                    "Whisper transcribeDataWithTime() done in ${t1 - t0} ms (segments=${segments.size})"
                )
                segments
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }

    suspend fun transcribeDataDirect(samples: FloatArray): String =
        withContext(Dispatchers.Default) {
            val c = ensureCtx()
            try {
                c.transcribeData(samples)
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }

    /**
     * Libère le contexte (optionnel à l’arrêt de l’app).
     */
    fun release() {
        val c = ctx ?: return
        try {
            c.release()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "release failed", t)
        } finally {
            ctx = null
        }
    }

    // --- helpers ---
    private fun ensureCtx(): WhisperContext =
        ctx ?: error("Whisper model not loaded yet. Call ensureLoaded() beforehand.")
}
