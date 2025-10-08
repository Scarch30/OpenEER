package com.example.openeer.workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.media.AudioFromVideoExtractor
import com.example.openeer.media.AudioDenoiser
import com.example.openeer.media.WavWriter
import com.example.openeer.media.decodeWaveFile
import com.example.openeer.services.WhisperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * InputData :
 *  - "videoUri": String (Uri)
 *  - "noteId": Long
 *  - "groupId": String  // UUID dans ton modèle
 *  - "videoBlockId": Long
 */
class VideoToTextWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VideoToTextWorker"
        private const val NOTIF_CHANNEL_ID = "transcription"
        private const val NOTIF_ID = 1002

        fun enqueue(
            context: Context,
            videoUri: Uri,
            noteId: Long,
            groupId: String,
            videoBlockId: Long
        ) {
            Log.d(TAG, "Enqueue worker: uri=$videoUri noteId=$noteId groupId=$groupId videoBlockId=$videoBlockId")
            val request = OneTimeWorkRequestBuilder<VideoToTextWorker>()
                .setInputData(
                    workDataOf(
                        "videoUri"      to videoUri.toString(),
                        "noteId"        to noteId,
                        "groupId"       to groupId,
                        "videoBlockId"  to videoBlockId
                    )
                )
                // Important avec targetSdk 34 : autorise WorkManager à démarrer le FGS même si l’app passe en arrière-plan
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("video_tx")
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Enqueued VideoToTextWorker. Tag='video_tx'")
        }
    }

    private val db by lazy { AppDatabase.get(applicationContext) }
    private val blocksRepo by lazy { BlocksRepository(db) }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Channel (API 26+)
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                Log.d(TAG, "Notification channel '$NOTIF_CHANNEL_ID' created")
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "Transcription",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Transcription audio/vidéo en arrière-plan"
                        setShowBadge(false)
                    }
                )
            }
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Transcription vidéo en cours…")
            .setSmallIcon(R.mipmap.ic_launcher) // icône existante
            .setOngoing(true)
            .build()

        // Android 14+ : préciser le type FGS (dataSync), sinon Lint/Runtime plantent.
        return ForegroundInfo(
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoUriStr = inputData.getString("videoUri")
        val noteId = inputData.getLong("noteId", -1L)
        val groupId = inputData.getString("groupId")
        val videoBlockId = inputData.getLong("videoBlockId", -1L)

        Log.d(TAG, "START doWork: uri=$videoUriStr noteId=$noteId groupId=$groupId videoBlockId=$videoBlockId")

        if (videoUriStr.isNullOrBlank() || groupId.isNullOrBlank() || noteId <= 0 || videoBlockId <= 0) {
            Log.e(TAG, "InputData invalide")
            return@withContext Result.failure()
        }

        val tmpDir = File(applicationContext.filesDir, "tmp").apply { mkdirs() }
        val rawWav = File(tmpDir, "${groupId}_video.wav")
        val denoisedWav = File(tmpDir, "${groupId}_video_denoised.wav")

        try {
            // Monter en foreground avant les opérations longues
            setForeground(getForegroundInfo())

            // Charger Whisper (idempotent)
            Log.d(TAG, "Whisper.loadModel()…")
            WhisperService.loadModel(applicationContext)
            Log.d(TAG, "Whisper.loadModel() OK")

            // Extraction WAV mono 16 kHz
            Log.d(TAG, "ExtractToWav -> ${rawWav.absolutePath}")
            val srcUri = Uri.parse(videoUriStr)

            // Logs internes de l'extracteur
            Log.d("AVExtract", "setDataSource($srcUri)")
            AudioFromVideoExtractor(applicationContext).extractToWav(
                videoUri = srcUri,
                outWavFile = rawWav,
                targetHz = 16_000
            )
            Log.d(TAG, "WAV OK (${rawWav.length()} bytes)")

            // === DÉBRUITAGE centralisé (AudioDenoiser) ===
            val sr = 16_000
            val raw = decodeWaveFile(rawWav) // FloatArray [-1,1] @16k mono
            Log.d(TAG, "🎛 DENOISER ▶ AudioDenoiser: in samples=${raw.size} (~${raw.size * 1000L / sr} ms)")
            val rmsBefore = frameRmsDb(raw, sr)
            val tD0 = System.currentTimeMillis()
            val denoised = AudioDenoiser.denoise(raw, sr)
            val tD1 = System.currentTimeMillis()
            val rmsAfter = frameRmsDb(denoised, sr)
            Log.d(TAG, "🎛 DENOISER ✓ AudioDenoiser done in ${tD1 - tD0} ms | avgRmsDb ${"%.1f".format(rmsBefore)} → ${"%.1f".format(rmsAfter)} (Δ=${"%.1f".format(rmsAfter - rmsBefore)} dB)")

            // (Optionnel mais pratique au debug) : écrire un WAV débruité
            writeDenoisedWav(denoised, sr, denoisedWav)
            Log.d(TAG, "WAV (denoised) written -> ${denoisedWav.absolutePath} (${denoisedWav.length()} bytes)")

            // Transcription SILENCE-AWARE sur le WAV débruité
            Log.d(TAG, "WHISPER ▶ transcribeWavSilenceAware(denoised=true)…")
            val tW0 = System.currentTimeMillis()
            val text = WhisperService.transcribeWavSilenceAware(denoisedWav)
            Log.d(TAG, "WHISPER ✓ done in ${System.currentTimeMillis() - tW0} ms (len=${text.length})")

            // MAJ bloc vidéo + création note-fille TEXT
            Log.d(TAG, "Repo.updateVideoTranscription(videoId=$videoBlockId)")
            blocksRepo.updateVideoTranscription(videoBlockId, text)

            Log.d(TAG, "Repo.appendTranscription(noteId=$noteId, groupId=$groupId)")
            blocksRepo.appendTranscription(
                targetNoteId = noteId,
                text = text,
                groupId = groupId,
                sourceMediaBlockId = videoBlockId
            )

            Result.success()
        } catch (e: SecurityException) {
            Log.e(TAG, "SECURITY fail: ${e.message}", e)
            Result.retry()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "STATE fail: ${e.message}", e)
            Result.retry()
        } catch (t: Throwable) {
            Log.e(TAG, "FAIL: ${t.message}", t)
            Result.retry()
        } finally {
            // Nettoyage
            runCatching {
                if (rawWav.exists()) {
                    val ok = rawWav.delete()
                    Log.d(TAG, "Temp RAW WAV delete=$ok")
                }
            }
            runCatching {
                if (denoisedWav.exists()) {
                    val ok = denoisedWav.delete()
                    Log.d(TAG, "Temp DENOISED WAV delete=$ok")
                }
            }
        }
    }

    // --- Utils ---

    /** Écrit un FloatArray [-1,1] en WAV PCM 16-bit mono @sampleRate via WavWriter. */
    private fun writeDenoisedWav(samples: FloatArray, sampleRate: Int, outFile: File) {
        val pcm = floatArrayToPcm16(samples)
        WavWriter(outFile, sampleRate, /*channels*/1, /*bitsPerSample*/16).use { w ->
            w.writeSamples(pcm)
        }
    }

    /** Conversion FloatArray ([-1,1]) -> ShortArray (PCM16) avec clipping/arrondi. */
    private fun floatArrayToPcm16(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        var i = 0
        while (i < src.size) {
            val f = src[i].coerceIn(-1f, 1f)
            val s = (f * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[i] = s.toShort()
            i++
        }
        return out
    }
}

/** Petit util pour log dB moyen (contrôle) */
private fun frameRmsDb(data: FloatArray, sampleRate: Int, frameMs: Int = 20): Double {
    val hop = max(1, sampleRate * frameMs / 1000)
    val frames = max(1, (data.size + hop - 1) / hop)
    var sum = 0.0
    for (f in 0 until frames) {
        val from = f * hop
        val to = min(from + hop, data.size)
        val n = (to - from).coerceAtLeast(1)
        var acc = 0.0
        var i = from
        while (i < to) {
            val v = data[i].toDouble()
            acc += v * v
            i++
        }
        val rms = sqrt(acc / n).coerceAtLeast(1e-12)
        sum += 20.0 * ln(rms) / ln(10.0)
    }
    return sum / frames
}
