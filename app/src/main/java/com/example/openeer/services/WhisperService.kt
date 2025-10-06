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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Façade minimale au-dessus de la lib Java com.whispercpp.java.whisper.*
 * - init/load du modèle depuis assets
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
     * Peut être appelé en arrière-plan après l’écran d’accueil.
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

    // --------------------------------------------------------------------
    //  TRANSCRIPTION - API PUBLIQUE
    // --------------------------------------------------------------------

    /**
     * Transcrit un fichier WAV (mono 16 kHz PCM 16-bit) et renvoie le texte concaténé.
     *
     * NOTE: on utilise par défaut la transcription “silence-aware” (avec coupe-circuit de durée
     * et chevauchement) pour mieux récupérer les fins de vidéos après marmonnement/bruit.
     * Si tu veux l’ancienne version monolithique, remplace l’appel par `transcribeWavSimple(wavFile)`.
     */
    suspend fun transcribeWav(wavFile: File): String = withContext(Dispatchers.Default) {
        transcribeWavSilenceAware(
            wavFile = wavFile,
            silenceThresholdDb = -42.0, // plus bas = plus tolérant au bruit
            minSilenceMs = 700,
            maxChunkMs = 6500,          // coupe obligatoire si pas de “vrai silence” assez long
            overlapMs = 200
        )
    }

    /**
     * Version “simple” (monolithique) si tu veux forcer l’ancien comportement.
     */
    suspend fun transcribeWavSimple(wavFile: File): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        Log.d(LOG_TAG, "Decoding WAV: ${wavFile.absolutePath}")
        val startDecode = System.currentTimeMillis()
        val floats = decodeWaveFile(wavFile) // FloatArray normalisée [-1,1] @16kHz mono
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

    /**
     * Transcription “silence-aware” + coupe-circuit durée + chevauchement.
     * Corrige les cas : 3 s parlées + 5 s marmonnées/bruitées + 5 s parlées (13 s au total),
     * où l’ancienne approche ratait la fin.
     */
    suspend fun transcribeWavSilenceAware(
        wavFile: File,
        silenceThresholdDb: Double = -40.0,
        minSilenceMs: Int = 700,
        maxChunkMs: Int = 6500,
        overlapMs: Int = 200,
        hopMs: Int = 20,              // fenêtre d’analyse RMS (20 ms)
        minKeepMs: Int = 500          // on évite des segments < 0.5 s
    ): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()

        // 1) Décodage WAV -> FloatArray mono 16kHz
        Log.d(LOG_TAG, "Decoding WAV (silence-aware): ${wavFile.absolutePath}")
        val floats = decodeWaveFile(wavFile)
        val sr = 16_000
        Log.d(
            LOG_TAG,
            "WAV decoded: samples=${floats.size} sr=$sr durMs=${floats.size * 1000L / sr}"
        )

        if (floats.isEmpty()) return@withContext ""

        // 2) Calcul dB RMS par trames courtes
        val hop = sr * hopMs / 1000
        val frameCount = (floats.size + hop - 1) / hop
        val dbFrames = IntArray(frameCount)
        for (f in 0 until frameCount) {
            val from = f * hop
            val to = min(from + hop, floats.size)
            dbFrames[f] = (rmsDb(floats, from, to - from) * 100).toInt() // *100 pour garder de la précision en Int
        }

        val minSilenceFrames = (minSilenceMs + hopMs - 1) / hopMs
        val maxChunkFrames   = maxChunkMs / hopMs
        val minKeepSamples   = max(1, sr * minKeepMs / 1000)
        val overlapSamples   = max(0, sr * overlapMs / 1000)

        // 3) Détection des coupures : (a) longs silences, (b) coupe durée max
        val cuts = mutableListOf(0) // indices en samples
        var runSilence = 0
        var lastCutFrame = 0
        val silenceIntThresh = (silenceThresholdDb * 100).toInt()

        for (f in 0 until frameCount) {
            val isSilent = dbFrames[f] < silenceIntThresh
            runSilence = if (isSilent) runSilence + 1 else 0

            val reachedMax   = (f - lastCutFrame) >= maxChunkFrames
            val longSilence  = runSilence >= minSilenceFrames

            if (longSilence || reachedMax) {
                // si long silence: coupe vers le milieu du plateau silencieux
                val cutFrame = if (longSilence) (f - runSilence / 2) else f
                val cutSample = (cutFrame * hop).coerceIn(0, floats.size)
                if (cutSample - cuts.last() >= minKeepSamples) {
                    cuts += cutSample
                    lastCutFrame = cutFrame
                    runSilence = 0
                }
            }
        }
        if (cuts.last() != floats.size) cuts += floats.size

        Log.d(LOG_TAG, "Silence-aware cuts: ${cuts.size - 1} segments (cuts=$cuts)")

        // 4) Transcription segment par segment (avec chevauchement)
        val sb = StringBuilder()
        for (i in 0 until cuts.size - 1) {
            var start = cuts[i]
            var end   = cuts[i + 1]

            // chevauchement
            if (i > 0) start = max(0, start - overlapSamples)
            if (i < cuts.size - 2) end = min(floats.size, end + overlapSamples)

            val segLen = end - start
            if (segLen < minKeepSamples) continue

            val segment = sliceFloat(floats, start, end)

            try {
                val t0 = System.currentTimeMillis()
                val txt = c.transcribeData(segment)
                val t1 = System.currentTimeMillis()
                Log.d(
                    LOG_TAG,
                    "Segment ${i + 1}/${cuts.size - 1}: samples=$segLen (~${segLen * 1000L / sr} ms) -> ${t1 - t0} ms; text='${txt.take(80)}${if (txt.length > 80) "…" else ""}'"
                )

                val clean = txt.trim()
                if (clean.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(clean)
                }
            } catch (e: ExecutionException) {
                val cause = (e.cause ?: e)
                Log.w(LOG_TAG, "Segment ${i + 1} failed: ${cause.message}", cause)
                // on continue avec les segments suivants
            }
        }

        sb.toString()
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

    /**
     * RMS -> dBFS sur sous-tableau de FloatArray normalisé [-1, 1].
     */
    private fun rmsDb(data: FloatArray, from: Int, len: Int): Double {
        if (len <= 0) return -120.0
        val end = min(from + len, data.size)
        var acc = 0.0
        var n = 0
        var i = from
        while (i < end) {
            val v = data[i].toDouble()
            acc += v * v
            n++
            i++
        }
        if (n == 0) return -120.0
        val rms = sqrt(acc / n).coerceAtLeast(1e-12)
        // dBFS: 20*log10(rms)
        return 20.0 * ln(rms) / ln(10.0)
    }

    private fun sliceFloat(src: FloatArray, start: Int, end: Int): FloatArray {
        val s = max(0, start)
        val e = min(src.size, end)
        val out = FloatArray(max(0, e - s))
        if (out.isNotEmpty()) {
            System.arraycopy(src, s, out, 0, out.size)
        }
        return out
    }
}
