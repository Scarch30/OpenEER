package com.example.openeer.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import kotlinx.coroutines.*
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.stt.VoskTranscriber
import java.io.File
import android.util.Log
import com.example.openeer.media.decodeWaveFile
import com.example.openeer.media.AudioDenoiser
import com.example.openeer.services.WhisperService

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.openeer.action.START"
        const val ACTION_STOP  = "com.example.openeer.action.STOP"
        const val CH_ID = "recorder"
        const val NOTIF_ID = 42
        const val BR_DONE = "com.example.openeer.recorder.DONE"
        const val EXTRA_PATH = "path"
        const val EXTRA_ERROR = "error"
        const val EXTRA_NOTE_ID = "noteId"

        private const val TAG = "RecorderService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: NoteRepository
    private lateinit var blocksRepo: BlocksRepository
    private var noteId: Long = 0L
    private var pcm: PcmRecorder? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repo = NoteRepository(db.noteDao(), db.attachmentDao())
        // ✅ Injection du linkDao pour activer la création des liens AUDIO→TEXTE
        blocksRepo = BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = null,
            linkDao  = db.blockLinkDao()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRec()
            ACTION_STOP  -> stopRec()
        }
        return START_NOT_STICKY
    }

    private fun startRec() {
        if (pcm != null) return
        ensureChannel()

        // Crée une note tout de suite (capture-first)
        scope.launch {
            val place = runCatching { getOneShotPlace(this@RecorderService) }.getOrNull()
            noteId = repo.createTextNote(
                body  = "(audio en cours d’enregistrement…)",
                lat   = place?.lat, lon = place?.lon, place = place?.label
            )
            if (place != null) {
                blocksRepo.appendLocation(noteId, place.lat, place.lon, place.label)
            }
        }

        pcm = PcmRecorder(this).also { rec ->
            // (optionnel) callback live si un jour tu veux streamer vers Vosk
            rec.onPcmChunk = { /* feed live ici si besoin */ }
            rec.start()
        }

        val notif = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("Enregistrement en cours")
            .setContentText("OpenEER (PCM 16 kHz mono)")
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun stopRec() {
        val rec = pcm ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        pcm = null

        scope.launch {
            // Sécurise l’arrêt + conversion WAV
            runCatching { rec.stop() }
            val wavPath = runCatching { rec.finalizeToWav() }.getOrNull()
            val wavFile = wavPath?.let { File(it) }

            var audioBlockId: Long? = null
            var groupId: String? = null

            if (noteId != 0L && wavFile != null) {
                val gid = generateGroupId()
                groupId = gid
                // ➕ Ajoute le bloc AUDIO dans la note (pile = groupId)
                audioBlockId = runCatching {
                    blocksRepo.appendAudio(
                        noteId    = noteId,
                        mediaUri  = wavPath,
                        durationMs= null,
                        mimeType  = "audio/wav",
                        groupId   = gid
                    )
                }.getOrNull()

                // 🔊 Débruitage + Whisper (avec logs)
                val transcription: String? = runCatching {
                    // 0) Charge Whisper si besoin
                    WhisperService.ensureLoaded(applicationContext)

                    // 1) Lecture WAV (FloatArray mono 16 kHz)
                    val samples = decodeWaveFile(wavFile)
                    val sr = 16_000
                    Log.d(TAG, "DENOISER ▶ in: samples=${samples.size}, durMs=${samples.size * 1000L / sr}, file=${wavFile.name}")

                    // 2) Débruitage en mémoire
                    val t0 = System.currentTimeMillis()
                    val denoised = AudioDenoiser.denoise(samples)
                    val t1 = System.currentTimeMillis()
                    Log.d(TAG, "DENOISER ✓ out: samples=${denoised.size}, took=${t1 - t0}ms")

                    // 3) Transcription Whisper
                    Log.d(TAG, "WHISPER ▶ start (mic, denoised=true)")
                    val tW = System.currentTimeMillis()
                    val text = WhisperService.transcribeDataDirect(denoised)
                    Log.d(TAG, "WHISPER ✓ done in ${System.currentTimeMillis() - tW}ms (mic)")
                    text
                }.getOrElse { e ->
                    Log.w(TAG, "Whisper fail (fallback Vosk): ${e.message}", e)
                    null
                }

                val finalText = transcription ?: runCatching {
                    // 🔁 Fallback Vosk si Whisper a échoué
                    Log.d(TAG, "VOSK ▶ fallback start")
                    val tV = System.currentTimeMillis()
                    val text = VoskTranscriber.transcribe(this@RecorderService, wavFile)
                    Log.d(TAG, "VOSK ✓ fallback done in ${System.currentTimeMillis() - tV}ms")
                    text
                }.getOrNull()

                if (!finalText.isNullOrBlank()) {
                    // 4) Mets à jour le corps de la note (affichage principal)
                    runCatching { repo.setBody(noteId, finalText) }

                    // 5) Mets à jour le bloc AUDIO et ajoute un bloc TEXT lié dans la même pile
                    audioBlockId?.let { aid ->
                        runCatching { blocksRepo.updateAudioTranscription(aid, finalText) }
                    }
                    runCatching {
                        // crée un bloc TEXT "transcription" dans la même pile (groupId)
                        blocksRepo.appendTranscription(
                            noteId = noteId,
                            text   = finalText,
                            groupId= groupId ?: generateGroupId()
                        )
                    }
                } else {
                    Log.w(TAG, "Aucune transcription obtenue (Whisper et Vosk).")
                }
            }

            // Broadcast retour (noteId + chemin wav)
            sendBroadcast(Intent(BR_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_PATH, wavPath)
                putExtra(EXTRA_NOTE_ID, noteId)
            })

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            noteId = 0L
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Enregistreur", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { pcm?.stop() } catch (_: Throwable) {}
        pcm = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
