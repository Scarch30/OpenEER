// app/src/main/java/com/example/openeer/media/AudioDenoiser.kt
package com.example.openeer.media

import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Débruiteur ultra-léger en mémoire (pas de dépendances natives).
 *
 * Objectif : atténuer le bruit large-bande/souffle avant Whisper,
 * sans déformer la voix. Filtre "spectral gating" simplifié :
 *  - on estime un plancher de bruit au début du signal
 *  - on calcule une énergie RMS par trames (overlap)
 *  - on applique un gain doux (0.15..1.0) quand l’énergie ≈ bruit
 *
 * Amplification post-traitement :
 *  - auto-gain jusqu’à +10 dB max
 *  - headroom anti-clip basé sur le pic (0.99 plein-échelle)
 *  - soft limiter très léger au-dessus d’un seuil pour lisser les rares dépassements
 *
 * Pour un résultat plus propre : remplacer par RNNoise quand tu voudras.
 */
object AudioDenoiser {

    private const val LOG_TAG_DEFAULT = "AudioDenoiser"

    // Tramage RMS
    private const val FRAME_MS = 20      // taille de trame
    private const val HOP_MS   = 10      // pas
    private const val NOISE_REF_MS = 400 // durée pour estimer le bruit en début de fichier

    // Seuils autour du plancher de bruit (en dB, convertis ensuite en lin)
    private const val GATE_SOFT_DB = 6.0    // > bruit * ~2.0  => gain ≈ 1
    private const val GATE_HARD_DB = -3.0   // ≈ bruit * ~0.7 => gain ≈ 0.15

    // Bornes de gain (débruitage)
    private const val MIN_GAIN = 0.15f
    private const val MAX_GAIN = 1.0f

    // Amplification post débruitage
    private const val AUTO_MAX_GAIN_DB = 10.0    // plafond +10 dB
    private const val PEAK_TARGET = 0.99f        // cible anti-clip (plein-échelle ~1.0)
    private const val LIMIT_SOFT_START = 0.92f   // début soft-limiter
    private const val LIMIT_SOFT_K = 4.0f        // pente du soft-limiter

    /**
     * Débruite un FloatArray mono [-1,1] et applique une **amplification auto** (max +10 dB).
     *
     * @param samples     signal
     * @param sampleRate  16000 par défaut
     * @param logTag      tag des logs
     * @param enableAutoGain active l’amplification auto plafonnée
     * @param maxGainDb   plafond d’amplification (par défaut +10 dB)
     */
    fun denoise(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        logTag: String = LOG_TAG_DEFAULT,
        enableAutoGain: Boolean = true,
        maxGainDb: Double = AUTO_MAX_GAIN_DB
    ): FloatArray {
        if (samples.isEmpty()) return samples

        val t0 = System.currentTimeMillis()
        val durMs = samples.size * 1000L / max(1, sampleRate)
        Log.d(logTag, "⚙️ Denoise: start (samples=${samples.size}, sr=$sampleRate, dur=${durMs}ms)")

        // === Stats d’entrée
        val inPeak = peakAbs(samples)
        val inRmsDb = rmsDbWhole(samples)

        // 1) RMS par trames (overlap)
        val frame = max(1, FRAME_MS * sampleRate / 1000)
        val hop   = max(1, HOP_MS   * sampleRate / 1000)
        val rms = frameRms(samples, frame, hop)

        // 2) Plancher de bruit sur les premières NOISE_REF_MS
        val refFrames = max(1, (NOISE_REF_MS * sampleRate / 1000) / hop)
        val use = min(refFrames, rms.size)
        var noiseMean = 0.0
        for (i in 0 until use) noiseMean += rms[i]
        noiseMean = if (use > 0) noiseMean / use else 1e-6

        // 3) Seuils (en lin)
        val softThresh = noiseMean * dbToLin(GATE_SOFT_DB)   // ~ x2.0
        val hardThresh = noiseMean * dbToLin(GATE_HARD_DB)   // ~ x0.7

        // 4) Gain par trame (S-curve entre hard/soft)
        val gains = FloatArray(rms.size) { idx ->
            val e = rms[idx]
            when {
                e <= hardThresh -> MIN_GAIN
                e >= softThresh -> MAX_GAIN
                else -> {
                    val a = (e - hardThresh) / max(1e-9, (softThresh - hardThresh))
                    (MIN_GAIN + (MAX_GAIN - MIN_GAIN) * (a * a).toFloat())
                }
            }
        }

        // 5) Application des gains (overlap-add)
        val denoised = applyGains(samples, gains, frame, hop)

        // 6) Logs qualité (SNR très approximatif -> tendance)
        val snrBefore = estimateSNR(samples, frame, hop)
        val snrAfter  = estimateSNR(denoised, frame, hop)
        val afterPeak = peakAbs(denoised)
        val afterRmsDb = rmsDbWhole(denoised)
        Log.d(logTag, "⚙️ Denoise: noiseRef=${"%.4f".format(noiseMean)} soft=${"%.4f".format(softThresh)} hard=${"%.4f".format(hardThresh)}")
        Log.d(logTag, "⚙️ Denoise: SNR before=${"%.2f".format(snrBefore)}dB -> after=${"%.2f".format(snrAfter)}dB, Δ=${"%.2f".format(snrAfter - snrBefore)}dB")
        Log.d(logTag, "📊 Levels: inPeak=${"%.3f".format(inPeak)} inRms=${"%.1f".format(inRmsDb)}dBFS | postDenoise peak=${"%.3f".format(afterPeak)} rms=${"%.1f".format(afterRmsDb)}dBFS")

        // 7) Amplification auto (plafonnée +10 dB et protégée par headroom)
        val out: FloatArray
        if (enableAutoGain) {
            val headroomDb = if (afterPeak <= 0f) 0.0 else linToDb((PEAK_TARGET / afterPeak).toDouble()).coerceAtLeast(0.0)
            val allowedDb = min(maxGainDb, headroomDb)
            val gainLin = dbToLin(allowedDb)

            val tGain0 = System.currentTimeMillis()
            val boosted = if (allowedDb > 0.01) applyGainWithSoftLimiter(denoised, gainLin) else denoised
            val tGain1 = System.currentTimeMillis()

            val bPeak = peakAbs(boosted)
            val bRmsDb = rmsDbWhole(boosted)
            Log.d(logTag, "🎚 AutoGain: requested≤${"%.1f".format(maxGainDb)}dB, headroom=${"%.1f".format(headroomDb)}dB, applied=${"%.1f".format(allowedDb)}dB in ${tGain1 - tGain0}ms")
            Log.d(logTag, "📊 AfterGain: peak=${"%.3f".format(bPeak)} rms=${"%.1f".format(bRmsDb)}dBFS")
            out = boosted
        } else {
            out = denoised
        }

        Log.d(logTag, "✅ Denoise: done in ${System.currentTimeMillis() - t0}ms")
        return out
    }

    // ---------- Helpers (niveau/frames) ----------

    private fun frameRms(x: FloatArray, frame: Int, hop: Int): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val nFrames = 1 + max(0, (x.size - frame) / hop)
        val out = DoubleArray(nFrames)
        var idx = 0
        for (f in 0 until nFrames) {
            var acc = 0.0
            var c = 0
            var i = 0
            while (i < frame && idx + i < x.size) {
                val v = x[idx + i].toDouble()
                acc += v * v
                c++
                i++
            }
            out[f] = sqrt(max(1e-12, acc / max(1, c)))
            idx += hop
        }
        return out
    }

    private fun applyGains(x: FloatArray, g: FloatArray, frame: Int, hop: Int): FloatArray {
        val out = FloatArray(x.size)
        val counts = IntArray(x.size)
        var idx = 0
        var fi = 0
        while (idx < x.size && fi < g.size) {
            val gain = g[fi]
            var i = 0
            while (i < frame && idx + i < x.size) {
                out[idx + i] += x[idx + i] * gain
                counts[idx + i] += 1
                i++
            }
            idx += hop
            fi++
        }
        // normalisation overlap
        for (i in out.indices) {
            val c = counts[i]
            if (c > 0) out[i] /= c.toFloat()
        }
        // clip léger de sécurité
        for (i in out.indices) {
            var v = out[i]
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            out[i] = v
        }
        return out
    }

    // ---------- Helpers (amplification/limiting) ----------

    /** Applique un gain linéaire puis un **soft-limiter** (knee doux) au-delà de LIMIT_SOFT_START. */
    private fun applyGainWithSoftLimiter(x: FloatArray, gainLin: Double): FloatArray {
        val out = FloatArray(x.size)
        val th = LIMIT_SOFT_START
        val k = LIMIT_SOFT_K
        for (i in x.indices) {
            var v = (x[i] * gainLin).toFloat()
            val av = abs(v)
            if (av > th) {
                // soft-knee simple (compression progressive)
                val over = (av - th) / (1f - th) // 0..1 au-dessus du seuil
                val comp = (1f - (over / (over + (1f / k))))  // 1 -> ~0.2
                v = if (v >= 0f) th + (1f - th) * comp else -(th + (1f - th) * comp)
            }
            // pare-chocs final
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            out[i] = v
        }
        return out
    }

    // ---------- Helpers (métriques + conversions) ----------

    private fun peakAbs(x: FloatArray): Float {
        var p = 0f
        for (v in x) {
            val a = abs(v)
            if (a > p) p = a
        }
        return p
    }

    private fun rmsDbWhole(x: FloatArray): Double {
        if (x.isEmpty()) return -120.0
        var acc = 0.0
        for (v in x) acc += v * v
        val rms = sqrt(acc / x.size).coerceAtLeast(1e-12)
        return 20.0 * ln(rms) / ln(10.0)
    }

    // SNR (dB) très approximatif (log d’orientation uniquement)
    private fun estimateSNR(x: FloatArray, frame: Int, hop: Int): Double {
        val rms = frameRms(x, frame, hop)
        if (rms.isEmpty()) return 0.0
        var signal = 0.0
        var noise = Double.MAX_VALUE
        for (e in rms) {
            signal = kotlin.math.max(signal, e)
            noise = kotlin.math.min(noise, e)
        }
        noise = kotlin.math.max(1e-9, noise)
        val ratio = signal / noise
        return 20.0 * ln(ratio) / ln(10.0)
    }

    private fun dbToLin(db: Double): Double = exp(db * ln(10.0) / 20.0)
    private fun linToDb(lin: Double): Double = 20.0 * ln(lin.coerceAtLeast(1e-12)) / ln(10.0)
}
