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
import com.example.openeer.services.WhisperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    private val blocksRepo by lazy {
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao = db.noteDao(),
            io = Dispatchers.IO,
            linkDao = db.blockLinkDao()
        )
    }

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

        try {
            // Monter en foreground avant les opérations longues
            setForeground(getForegroundInfo())

            // Charger Whisper (idempotent)
            Log.d(TAG, "Whisper.loadModel()…")
            WhisperService.loadModel(applicationContext)
            Log.d(TAG, "Whisper.loadModel() OK")

            // Extraction WAV mono 16 kHz
            val wav = File(applicationContext.filesDir, "tmp/${groupId}_video.wav").also {
                it.parentFile?.mkdirs()
            }
            Log.d(TAG, "ExtractToWav -> ${wav.absolutePath}")
            val srcUri = Uri.parse(videoUriStr)

            // Logs internes de l'extracteur
            Log.d("AVExtract", "setDataSource($srcUri)")
            AudioFromVideoExtractor(applicationContext).extractToWav(
                videoUri = srcUri,
                outWavFile = wav,
                targetHz = 16_000
            )
            Log.d(TAG, "WAV OK (${wav.length()} bytes)")

            // Transcription
            Log.d(TAG, "Whisper.transcribeWav()…")
            val text = WhisperService.transcribeWav(wav)
            Log.d(TAG, "Whisper.transcribeWav() OK (${text.length} chars)")

            // MAJ bloc vidéo + création note-fille TEXT
            Log.d(TAG, "Repo.updateVideoTranscription(videoId=$videoBlockId)")
            blocksRepo.updateVideoTranscription(videoBlockId, text)

            Log.d(TAG, "Repo.appendTranscription(noteId=$noteId, groupId=$groupId)")
            blocksRepo.appendTranscription(
                noteId = noteId,
                text = text,
                groupId = groupId
            )

            // Nettoyage
            runCatching {
                if (wav.exists()) {
                    val ok = wav.delete()
                    Log.d(TAG, "Temp WAV delete=${ok}")
                }
            }

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
        }
    }
}
