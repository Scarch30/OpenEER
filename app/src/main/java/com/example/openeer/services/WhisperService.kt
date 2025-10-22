package com.example.openeer.services

import android.content.Context
import android.util.Log
import com.example.openeer.media.decodeWaveFile
import com.example.openeer.media.AudioDenoiser
import com.whispercpp.java.whisper.WhisperContext
import com.whispercpp.java.whisper.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object WhisperService {

    private const val LOG_TAG = "WhisperService"
    private const val WHISPER_SAMPLE_RATE = 16_000

    @Volatile private var ctx: WhisperContext? = null
    private val loadMutex = Mutex()

    fun isLoaded(): Boolean = (ctx != null)

    suspend fun ensureLoaded(appCtx: Context, assetPath: String = "models/ggml-small-fr-q5_1.bin") {
        if (ctx != null) return
        loadModel(appCtx, assetPath)
    }

    suspend fun loadModel(appCtx: Context, assetPath: String = "models/ggml-small-fr-q5_1.bin") = withContext(Dispatchers.Default) {
        if (ctx != null) return@withContext
        loadMutex.withLock {
            if (ctx != null) return@withLock
            try {
                Log.d(LOG_TAG, "Whisper system: ${WhisperContext.getSystemInfo()}")
            } catch (_: Throwable) { /* no-op */ }

            Log.d(LOG_TAG, "Loading Whisper model from assets: $assetPath")
            ctx = WhisperContext.createContextFromAsset(appCtx.assets, assetPath)
            Log.d(LOG_TAG, "Whisper model loaded from assets: $assetPath")
        }
    }

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

    // =========================================================
    //  Fonctions de transcription + intégration AudioDenoiser
    // =========================================================

    suspend fun transcribeWav(wavFile: File): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        var floats = decodeWaveFile(wavFile)
        Log.d(LOG_TAG, "transcribeWav: samples=${floats.size} sr=$WHISPER_SAMPLE_RATE")

        // --- Débruitage AudioDenoiser ---
        val t0 = System.currentTimeMillis()
        val rmsBefore = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        floats = AudioDenoiser.denoise(floats)
        val rmsAfter = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (transcribeWav) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        try {
            val start = System.currentTimeMillis()
            val text = c.transcribeData(floats, WHISPER_SAMPLE_RATE)
            Log.d(LOG_TAG, "Whisper done in ${System.currentTimeMillis() - start} ms")
            text
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    suspend fun transcribeDataDirect(samples: FloatArray): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()

        var floats = samples
        val t0 = System.currentTimeMillis()
        val rmsBefore = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        floats = AudioDenoiser.denoise(floats)
        val rmsAfter = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (transcribeDataDirect) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        try {
            c.transcribeData(floats, WHISPER_SAMPLE_RATE)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    suspend fun transcribeWavWithTimestamps(wavFile: File): List<WhisperSegment> = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        var floats = decodeWaveFile(wavFile)
        Log.d(LOG_TAG, "transcribeWavWithTimestamps: samples=${floats.size} sr=$WHISPER_SAMPLE_RATE")

        // --- Débruitage AudioDenoiser ---
        val t0 = System.currentTimeMillis()
        val rmsBefore = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        floats = AudioDenoiser.denoise(floats)
        val rmsAfter = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (withTimestamps) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        try {
            val start = System.currentTimeMillis()
            val segments = c.transcribeDataWithTime(floats, WHISPER_SAMPLE_RATE)
            Log.d(LOG_TAG, "Whisper with timestamps done in ${System.currentTimeMillis() - start} ms")
            segments
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    suspend fun transcribeWavSilenceAware(wavFile: File): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        var samples = decodeWaveFile(wavFile)
        val sr = WHISPER_SAMPLE_RATE

        // --- Débruitage AudioDenoiser ---
        val t0 = System.currentTimeMillis()
        val rmsBefore = samples.map { it * it }.average().let { sqrt(it).toFloat() }
        samples = AudioDenoiser.denoise(samples)
        val rmsAfter = samples.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (silenceAware) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        Log.d(LOG_TAG, "Silence-aware transcription: samples=${samples.size} sr=$sr")

        val speechMask = detectSpeechMask(samples, sr)
        val speechIslands = maskToIslands(speechMask, sr)
        if (speechIslands.isEmpty()) {
            Log.d(LOG_TAG, "No speech detected -> empty result")
            return@withContext ""
        }

        val windows = buildWindowsAroundIslands(speechIslands, samples.size, sr)
        Log.d(LOG_TAG, "Built ${windows.size} windows")

        val sb = StringBuilder()
        var idx = 0
        for (w in windows) {
            idx++
            val seg = samples.copyOfRange(w.first, w.second)
            val text = try {
                val t1 = System.currentTimeMillis()
                val out = withTimeoutOrNull(15000L) { c.transcribeData(seg, sr) }
                val dt2 = System.currentTimeMillis() - t1
                Log.d(LOG_TAG, "Whisper window $idx/${windows.size} done in ${dt2}ms -> '${out?.take(60)}'")
                out ?: ""
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Whisper error on window $idx: ${e.message}")
                ""
            }
            if (text.isNotBlank()) sb.append(' ').append(text.trim())
        }
        sb.toString().trim()
    }

    // =========================================================
    //   Fonctions internes : VAD + détection silence
    // =========================================================

    private fun ensureCtx(): WhisperContext =
        ctx ?: error("Whisper model not loaded yet. Call ensureLoaded().")

    private fun detectSpeechMask(samples: FloatArray, sr: Int): BooleanArray {
        val hop = max(1, (40 * sr / 1000.0).toInt())
        val rms = movingRms(samples, hop)
        val dbfs = DoubleArray(rms.size) { i ->
            val v = max(1e-7, rms[i].toDouble())
            20.0 * ln(v) / ln(10.0)
        }
        val vad = BooleanArray(dbfs.size) { i -> dbfs[i] > -38.0 }
        return upsampleMask(vad, samples.size, hop)
    }

    private fun movingRms(x: FloatArray, hop: Int): FloatArray {
        val n = x.size
        if (n == 0) return FloatArray(0)
        val out = FloatArray((n + hop - 1) / hop)
        var acc = 0.0
        var cnt = 0
        var outIdx = 0
        for (i in 0 until n) {
            val v = x[i].toDouble()
            acc += v * v
            cnt++
            if ((i + 1) % hop == 0 || i == n - 1) {
                out[outIdx++] = sqrt(acc / cnt).toFloat()
                acc = 0.0
                cnt = 0
            }
        }
        return out
    }

    private fun maskToIslands(mask: BooleanArray, sr: Int): List<IntRange> {
        val res = ArrayList<IntRange>()
        var i = 0
        while (i < mask.size) {
            while (i < mask.size && !mask[i]) i++
            if (i >= mask.size) break
            val start = i
            while (i < mask.size && mask[i]) i++
            val end = i
            res += (start until end)
        }
        return res
    }

    private fun buildWindowsAroundIslands(islands: List<IntRange>, totalSamples: Int, sr: Int): List<Pair<Int, Int>> {
        val minS = (4.0 * sr).toInt()
        val maxS = (12.0 * sr).toInt()
        val ovl = (0.4 * sr).toInt()

        val windows = ArrayList<Pair<Int, Int>>()
        var curStart = max(0, islands.first().first - ovl)
        var curEnd = min(totalSamples, islands.first().last + ovl)

        fun flush() {
            var s = curStart
            var e = curEnd
            if (e - s < minS) e = min(totalSamples, s + minS)
            var t0 = s
            while (e - t0 > maxS) {
                val t1 = t0 + maxS
                windows += (t0 to t1)
                t0 = t1 - ovl
            }
            windows += (t0 to e)
        }

        for (k in 1 until islands.size) {
            val next = islands[k]
            val canGrow = (next.last + ovl) - curStart <= maxS
            if (canGrow) {
                curEnd = min(totalSamples, max(curEnd, next.last + ovl))
            } else {
                flush()
                curStart = max(0, next.first - ovl)
                curEnd = min(totalSamples, next.last + ovl)
            }
        }
        flush()
        return windows
    }

    private fun upsampleMask(mask: BooleanArray, targetSamples: Int, hop: Int): BooleanArray {
        val out = BooleanArray(targetSamples)
        for (i in out.indices) {
            val idx = min(mask.lastIndex, i / hop)
            out[i] = mask[idx]
        }
        return out
    }
}
