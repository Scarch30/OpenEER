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
import kotlinx.coroutines.*
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.stt.VoskTranscriber
import java.io.File

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: NoteRepository
    private var noteId: Long = 0L
    private var pcm: PcmRecorder? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repo = NoteRepository(db.noteDao(), db.attachmentDao())
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
            val wav = runCatching { rec.finalizeToWav() }.getOrNull()

            if (noteId != 0L && wav != null) {
                runCatching { repo.updateAudio(noteId, wav) }

                val text = runCatching {
                    VoskTranscriber.transcribe(this@RecorderService, File(wav))
                }.getOrNull()

                if (!text.isNullOrBlank()) {
                    // ✨ FIX: setBody ne prend que (id, body)
                    runCatching { repo.setBody(noteId, text) }
                }
            }

            // Broadcast retour (noteId + chemin wav)
            sendBroadcast(Intent(BR_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_PATH, wav)
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
