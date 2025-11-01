package com.example.openeer.services

import android.content.Context
import android.util.Log
import com.example.openeer.media.AudioDenoiser
import com.example.openeer.media.decodeWaveFile
import com.example.openeer.text.FrNumberITN
import com.example.openeer.text.FrNumberSecondPass
import com.whispercpp.java.whisper.WhisperContext
import com.whispercpp.java.whisper.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.Locale

object WhisperService {

    private const val LOG_TAG = "WhisperService"

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

    // ---------------------------
    //  Pré-filtre "quatre-vingt(s)" avant ITN
    // ---------------------------
    private fun preFilterQuatreVingtAA(s: String): String {
        var out = s
        // Gestion des formes avec ou sans tirets et "vingts"
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?neuf\\b"), "99")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?huit\\b"), "98")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?sept\\b"), "97")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?six\\b"), "96")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?cinq\\b"), "95")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?quatre\\b"), "94")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?trois\\b"), "93")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?deux\\b"), "92")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?onze\\b"), "91")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix\\b"), "90")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?neuf\\b"), "89")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?huit\\b"), "88")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?sept\\b"), "87")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?six\\b"), "86")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?cinq\\b"), "85")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?quatre\\b"), "84")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?trois\\b"), "83")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?deux\\b"), "82")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?un\\b"), "81")
        out = out.replace(Regex("(?i)\\bquatre[-\\s]?vingts?\\b"), "80")
        return out
    }

    // ---------------------------
    //  Normalisation 2 passes
    // ---------------------------
    private fun normalizeTwoPass(s: String): String {
        // 0) Pré-filtre spécial pour éviter la casse 4+20+1 -> 25
        val pre = preFilterQuatreVingtAA(s)
        // 1) ITN sûr (lettres -> chiffres)
        val step1 = FrNumberITN.normalize(pre)
        // 2) Correctifs ciblés (cinquante -> 50, quatre-vingt(s) -> 80, etc.)
        return FrNumberSecondPass.apply(step1)
    }

    // ---------------------------
    //  Outils de debug Whisper
    // ---------------------------
    private fun visualizeSeparators(s: String): String = buildString {
        for (ch in s) {
            append(
                when (ch) {
                    ' ' -> '␠'
                    '\u00A0' -> '⍽'
                    '\u202F' -> '⨝'
                    '\u2009' -> ' '
                    '\u200A' -> ' '
                    '-' -> '‐'
                    '\u2010' -> '‐'
                    '\u2011' -> '-'
                    '\u2012' -> '‒'
                    '\u2013' -> '–'
                    '\u2014' -> '—'
                    '\t' -> '⇥'
                    '\n' -> '↩'
                    else -> ch
                }
            )
        }
    }

    private fun codePointsHex(s: String): String = buildString {
        val it = s.codePoints().iterator()
        var first = true
        while (it.hasNext()) {
            if (!first) append(' ')
            val cp = it.nextInt()
            append(String.format(Locale.ROOT, "U+%04X", cp))
            first = false
        }
    }

    private fun tokensView(s: String): String =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" | ") { "|$it|" }

    private fun logWhisperRaw(tagSuffix: String, text: String) {
        Log.d(LOG_TAG, "[$tagSuffix] RAW: «$text»")
        Log.d(LOG_TAG, "[$tagSuffix] VIS: «${visualizeSeparators(text)}»")
        Log.d(LOG_TAG, "[$tagSuffix] HEX: ${codePointsHex(text)}")
        Log.d(LOG_TAG, "[$tagSuffix] TOK: ${tokensView(text)}")
    }

    // =========================================================
    //  Fonctions de transcription + débruitage
    // =========================================================
    suspend fun transcribeWav(wavFile: File): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        var floats = decodeWaveFile(wavFile)
        Log.d(LOG_TAG, "transcribeWav: samples=${floats.size} sr=16000")

        val t0 = System.currentTimeMillis()
        val rmsBefore = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        floats = AudioDenoiser.denoise(floats)
        val rmsAfter = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (transcribeWav) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        try {
            val start = System.currentTimeMillis()
            val text = c.transcribeData(floats)
            Log.d(LOG_TAG, "Whisper done in ${System.currentTimeMillis() - start} ms")
            logWhisperRaw("transcribeWav", text)
            normalizeTwoPass(text)
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
            val text = c.transcribeData(floats)
            logWhisperRaw("transcribeDataDirect", text)
            normalizeTwoPass(text)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    suspend fun transcribeWavWithTimestamps(wavFile: File): List<WhisperSegment> = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        var floats = decodeWaveFile(wavFile)
        Log.d(LOG_TAG, "transcribeWavWithTimestamps: samples=${floats.size}")

        val t0 = System.currentTimeMillis()
        val rmsBefore = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        floats = AudioDenoiser.denoise(floats)
        val rmsAfter = floats.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (withTimestamps) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        try {
            val start = System.currentTimeMillis()
            val segments = c.transcribeDataWithTime(floats)
            Log.d(LOG_TAG, "Whisper with timestamps done in ${System.currentTimeMillis() - start} ms")
            segments
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    suspend fun transcribeWavSilenceAware(wavFile: File): String = withContext(Dispatchers.Default) {
        val c = ensureCtx()
        var samples = decodeWaveFile(wavFile)
        val sr = 16_000

        val t0 = System.currentTimeMillis()
        val rmsBefore = samples.map { it * it }.average().let { sqrt(it).toFloat() }
        samples = AudioDenoiser.denoise(samples)
        val rmsAfter = samples.map { it * it }.average().let { sqrt(it).toFloat() }
        val dt = System.currentTimeMillis() - t0
        Log.d(LOG_TAG, "🔊 Denoiser applied (silenceAware) in ${dt}ms | RMS before=$rmsBefore RMS after=$rmsAfter")

        val speechMask = detectSpeechMask(samples, sr)
        val speechIslands = maskToIslands(speechMask, sr)
        if (speechIslands.isEmpty()) return@withContext ""

        val windows = buildWindowsAroundIslands(speechIslands, samples.size, sr)
        val sb = StringBuilder()
        var idx = 0
        for (w in windows) {
            idx++
            val seg = samples.copyOfRange(w.first, w.second)
            val text = try {
                val t1 = System.currentTimeMillis()
                val out = withTimeoutOrNull(15000L) { c.transcribeData(seg) }
                val dt2 = System.currentTimeMillis() - t1
                Log.d(LOG_TAG, "Whisper window $idx/${windows.size} done in ${dt2}ms -> '${out?.take(60)}'")
                out ?: ""
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Whisper error on window $idx: ${e.message}")
                ""
            }
            if (text.isNotBlank()) {
                logWhisperRaw("silenceAware#$idx", text)
                sb.append(' ').append(text.trim())
            }
        }
        val raw = sb.toString().trim()
        logWhisperRaw("silenceAware#merged", raw)
        normalizeTwoPass(raw)
    }

    // =========================================================
    //   Fonctions internes : VAD + silence
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
