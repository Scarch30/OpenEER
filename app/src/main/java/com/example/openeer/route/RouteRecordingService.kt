package com.example.openeer.route

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.data.route.RoutePersister
import com.example.openeer.ui.map.MapUiDefaults
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel // ✅ pour serviceScope.cancel()
import java.util.concurrent.atomic.AtomicInteger

class RouteRecordingService : Service(), LocationListener {

    companion object {
        private const val TAG = "RouteService"

        const val ACTION_START = "com.example.openeer.route.ACTION_START"
        const val ACTION_STOP  = "com.example.openeer.route.ACTION_STOP"

        const val ACTION_STATE  = "com.example.openeer.route.STATE"
        const val ACTION_POINTS = "com.example.openeer.route.POINTS"
        const val ACTION_SAVED  = "com.example.openeer.route.SAVED"

        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_STATE   = "extra_state"
        const val EXTRA_COUNT   = "extra_count"

        const val EXTRA_LAST_LAT = "extra_last_lat"
        const val EXTRA_LAST_LON = "extra_last_lon"
        const val EXTRA_LAST_TS  = "extra_last_ts"

        const val EXTRA_ROUTE_BLOCK_ID  = "extra_route_block_id"
        const val EXTRA_MIRROR_BLOCK_ID = "extra_mirror_block_id"

        private const val CHANNEL_ID = "route_recording"
        private const val NOTIF_ID   = 42
        private const val MIN_INTERVAL_MS: Long = 1500L

        /** Lance le service en mode foreground pour enregistrer un itinéraire GPS. */
        fun start(context: Context, noteId: Long) {
            val intent = Intent(context, RouteRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NOTE_ID, noteId)
            Log.d(TAG, "start(): noteId=$noteId")
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Arrête le service proprement et déclenche la sauvegarde du tracé. */
        fun stop(context: Context) {
            Log.d(TAG, "stop() requested")
            val intent = Intent(context, RouteRecordingService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** ✅ Version statique pour pouvoir l’appeler depuis OpenEERApp (et ici). */
        @JvmStatic
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.route_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.route_channel_desc)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var noteRepo: NoteRepository
    private lateinit var blocksRepo: BlocksRepository
    private val routeGson by lazy { Gson() }
    private lateinit var lm: LocationManager

    private val points = mutableListOf<RoutePointPayload>()
    private val pointCount = AtomicInteger(0)
    private var lastAcceptedAt: Long = 0L
    private var lastLocation: Location? = null
    private var targetNoteId: Long = 0L

    private val providers: List<String> by lazy {
        buildList {
            runCatching { if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER) }
            runCatching { if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER) }
            if (isEmpty()) add(LocationManager.PASSIVE_PROVIDER)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        // ⬇️ utilise la version statique du companion
        RouteRecordingService.ensureChannel(this)

        val db = AppDatabase.get(applicationContext)
        val noteDao       = db.noteDao()
        val blockDao      = db.blockDao()
        val attachmentDao = db.attachmentDao()
        val blockReadDao  = db.blockReadDao()

        blocksRepo = BlocksRepository(
            blockDao = blockDao,
            noteDao  = noteDao,
            linkDao  = db.blockLinkDao()
        )
        noteRepo   = NoteRepository(noteDao, attachmentDao, blockReadDao, blocksRepo)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetNoteId = intent.getLongExtra(EXTRA_NOTE_ID, 0L)
                Log.d(TAG, "onStartCommand(ACTION_START): noteId=$targetNoteId")
                startForeground(NOTIF_ID, buildNotif(getString(R.string.route_notif_running, 0)))
                emitState("STARTED")
                startListening()
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand(ACTION_STOP)")
                serviceScope.launch { persistAndStop() }
            }
            else -> Log.w(TAG, "onStartCommand(): unknown action=${intent?.action}")
        }
        return START_STICKY
    }

    private fun buildStopPendingIntent(): PendingIntent {
        val stop = Intent(this, RouteRecordingService::class.java).setAction(ACTION_STOP)
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(this, 1, stop, flags)
    }

    private fun buildNotif(content: String, ongoing: Boolean = true): Notification {
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stop, getString(R.string.route_action_stop), buildStopPendingIntent()
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_route_rec)
            .setContentTitle(getString(R.string.route_notif_title))
            .setContentText(content)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(stopAction)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotif(count: Int) {
        Log.d(TAG, "updateNotif(count=$count)")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(getString(R.string.route_notif_running, count)))
    }

    private fun emitState(state: String) {
        Log.d(TAG, "emitState($state)")
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
        )
    }

    @Suppress("MissingPermission")
    private fun startListening() {
        points.clear()
        pointCount.set(0)
        lastAcceptedAt = 0L
        lastLocation = null

        Log.d(TAG, "startListening(): providers=$providers, interval=${MapUiDefaults.REQUEST_INTERVAL_MS}ms")
        providers.forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(
                    provider,
                    MapUiDefaults.REQUEST_INTERVAL_MS,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "requestLocationUpdates(provider=$provider) OK")
            }.onFailure {
                Log.e(TAG, "requestLocationUpdates(provider=$provider) FAILED", it)
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastAcceptedAt < MIN_INTERVAL_MS) return
        lastLocation?.let { prev -> if (location.distanceTo(prev) < MapUiDefaults.MIN_DISTANCE_METERS) return }
        if (points.size >= MapUiDefaults.MAX_ROUTE_POINTS) return

        val p = RoutePointPayload(location.latitude, location.longitude, now)
        points.add(p)
        lastAcceptedAt = now
        lastLocation = Location(location)

        val c = pointCount.incrementAndGet()
        Log.d(TAG, "point #$c ${p.lat},${p.lon} @${p.t}")
        updateNotif(c)

        sendBroadcast(
            Intent(ACTION_POINTS)
                .setPackage(packageName)
                .putExtra(EXTRA_COUNT, c)
                .putExtra(EXTRA_LAST_LAT, p.lat)
                .putExtra(EXTRA_LAST_LON, p.lon)
                .putExtra(EXTRA_LAST_TS,  p.t)
        )
    }

    private suspend fun persistAndStop() {
        Log.d(TAG, "persistAndStop(): points=${points.size}, noteId=$targetNoteId")

        runCatching { providers.forEach { lm.removeUpdates(this) } }
            .onFailure { Log.w(TAG, "removeUpdates error", it) }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (points.size < 2) {
            Log.w(TAG, "Too few points → no route persisted")
            withContext(Dispatchers.Main) {
                nm.notify(NOTIF_ID, buildNotif(getString(R.string.route_notif_done_empty), ongoing = false))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                emitState("STOPPED")
            }
            return
        }

        val payload = RoutePayload(
            startedAt = points.first().t,
            endedAt   = points.last().t,
            points    = points.toList()
        )

        val mirrorText = "Itinéraire • ${points.size} pts • ${formatTime(payload.startedAt)} → ${formatTime(payload.endedAt)}"

        val result = runCatching {
            RoutePersister.persistRoute(
                noteRepo,
                blocksRepo,
                routeGson,
                targetNoteId,
                payload,
                mirrorText
            )
        }.onFailure { e ->
            Log.e(TAG, "persistRoute() failed", e)
        }.getOrNull()

        result?.let {
            Log.d(TAG, "persistAndStop(): saved note=${it.noteId} routeBlock=${it.routeBlockId}")
            sendBroadcast(
                Intent(ACTION_SAVED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_NOTE_ID, it.noteId)
                    .putExtra(EXTRA_ROUTE_BLOCK_ID, it.routeBlockId)
                    .putExtra(EXTRA_MIRROR_BLOCK_ID, it.mirrorBlockId)
                    .putExtra(EXTRA_COUNT, points.size)
            )
        }

        withContext(Dispatchers.Main) {
            nm.notify(
                NOTIF_ID,
                buildNotif(
                    if (result != null) getString(R.string.route_notif_done)
                    else getString(R.string.route_notif_done_empty),
                    ongoing = false
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            emitState("STOPPED")
        }
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm").format(java.util.Date(ts))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
