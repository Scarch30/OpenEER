package com.example.openeer.route

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.map.RouteRecorder
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class RouteRecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "route_recording_channel"
        private const val CHANNEL_NAME = "Enregistrement d’itinéraire"
        private const val NOTIF_ID = 4242

        private const val ACTION_START = "com.example.openeer.route.ACTION_START"
        private const val ACTION_STOP  = "com.example.openeer.route.ACTION_STOP"
        private const val EXTRA_NOTE_ID = "extra_note_id"

        fun start(context: Context, noteId: Long) {
            val i = Intent(context, RouteRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NOTE_ID, noteId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, RouteRecordingService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }

    // DB / Repos
    private val db by lazy { AppDatabase.get(applicationContext) }
    private val blocksRepo by lazy { BlocksRepository(db.blockDao(), db.noteDao(), io = Dispatchers.IO, linkDao = db.blockLinkDao()) }
    private val noteRepo   by lazy { NoteRepository(db.noteDao(), db.attachmentDao(), db.blockReadDao(), blocksRepo) }

    // Location
    private lateinit var lm: LocationManager
    private var recorder: RouteRecorder? = null

    // Contexte courant
    private var noteId: Long = 0L
    private val pointCount = AtomicInteger(0)

    // Scope service
    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                noteId = intent.getLongExtra(EXTRA_NOTE_ID, 0L)
                startInForeground()
                startRecorder()
            }
            ACTION_STOP -> {
                svcScope.launch { stopAndPersist() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder?.cancel()
        recorder = null
        svcScope.cancel()
    }

    // ---------- Foreground / notifications -----------

    private fun startInForeground() {
        val n = buildNotif("Enregistrement de l’itinéraire…", ongoing = true)
        startForeground(NOTIF_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(content: String, ongoing: Boolean): Notification {
        val openIntent = Intent(this, MapActivity::class.java)
        val contentPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, RouteRecordingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // ✅ icône appli par défaut
            .setContentTitle("OpenEER")
            .setContentText(content)
            .setContentIntent(contentPi)
            .setOngoing(ongoing)
            .addAction(0, "Arrêter", stopPi) // ✅ libellé inline
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotif(points: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif("Enregistrement en cours — $points pts", ongoing = true))
    }

    // --------------- Enregistrement ------------------

    private fun startRecorder() {
        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }.ifEmpty { listOf(LocationManager.PASSIVE_PROVIDER) }

        recorder = RouteRecorder(
            locationManager = lm,
            providers = providers
        ) { points ->
            val previous = pointCount.getAndSet(points.size)
            if (points.size != previous) updateNotif(points.size)
        }

        try {
            recorder?.start()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private suspend fun stopAndPersist() = withContext(Dispatchers.IO) {
        val payload: RoutePayload? = runCatching { recorder?.stop() }.getOrNull()
        recorder = null

        if (payload == null || payload.points.isNullOrEmpty() || noteId == 0L) {
            withContext(Dispatchers.Main) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotif("Enregistrement arrêté", ongoing = false))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return@withContext
        }

        val last = payload.points.last()
        runCatching {
            val json = com.google.gson.Gson().toJson(payload)
            blocksRepo.appendRoute(
                noteId = noteId,
                routeJson = json,
                lat = last.lat,
                lon = last.lon
            )
            // Optionnel : ancrer la note à la fin de l’itinéraire
            noteRepo.updateLocation(
                id = noteId,
                lat = last.lat,
                lon = last.lon,
                place = null,
                accuracyM = null
            )
        }

        withContext(Dispatchers.Main) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif("Itinéraire enregistré", ongoing = false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
