package com.example.openeer.route

import android.app.*
import android.content.*
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
import kotlinx.coroutines.*
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

        // Fallback local si non exposé par MapUiDefaults
        private const val MIN_INTERVAL_MS: Long = 1500L

        fun start(context: Context, noteId: Long) {
            val i = Intent(context, RouteRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NOTE_ID, noteId)
            Log.d(TAG, "start(): noteId=$noteId")
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) {
            Log.d(TAG, "stop() requested")
            val i = Intent(context, RouteRecordingService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

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

    // DI
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
        ensureChannel(this)

        // ⚠️ Utilise la factory présente dans ton AppDatabase
        val db = AppDatabase.get(applicationContext)
        val noteDao       = db.noteDao()
        val blockDao      = db.blockDao()
        val attachmentDao = db.attachmentDao()
        val blockReadDao  = db.blockReadDao()

        // Assure-toi que ces constructeurs correspondent à tes classes
        blocksRepo = BlocksRepository(
            blockDao = blockDao,
            noteDao = noteDao,
            linkDao = db.blockLinkDao()
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
            else -> {
                Log.w(TAG, "onStartCommand(): unknown action=${intent?.action}")
            }
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
                .setPackage(packageName)      // 🔒 cible ton app
                .putExtra(EXTRA_STATE, state)
        )
    }

    @Suppress("MissingPermission")
    private fun startListening() {
        points.clear()
        pointCount.set(0)
        lastAcceptedAt = 0L
        lastLocation = null

        Log.d(TAG, "startListening() providers=$providers")
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

        // Throttle temps
        if (now - lastAcceptedAt < MIN_INTERVAL_MS) return

        // Throttle distance
        lastLocation?.let { prev ->
            val d = location.distanceTo(prev)
            if (d < MapUiDefaults.MIN_DISTANCE_METERS) return
        }

        // Limite dure
        if (points.size >= MapUiDefaults.MAX_ROUTE_POINTS) return

        val point = RoutePointPayload(location.latitude, location.longitude, now)
        points.add(point)
        lastAcceptedAt = now
        lastLocation = Location(location)

        val c = pointCount.incrementAndGet()
        Log.d(TAG, "point #$c ${point.lat},${point.lon} @${point.t}")
        updateNotif(c)
        sendBroadcast(
            Intent(ACTION_POINTS)
                .setPackage(packageName)
                .putExtra(EXTRA_COUNT, c)
                .putExtra(EXTRA_LAST_LAT, point.lat)
                .putExtra(EXTRA_LAST_LON, point.lon)
                .putExtra(EXTRA_LAST_TS, point.t)
        )
    }

    private suspend fun persistAndStop() {
        Log.d(TAG, "persistAndStop(): points=${points.size}, noteId=$targetNoteId")

        // Arrête les updates
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
            endedAt = points.last().t,
            points = points.toList()
        )

        val mirrorText = "Itinéraire • ${points.size} pts • ${formatTime(payload.startedAt)} → ${formatTime(payload.endedAt)}"

        // Persistance via data-layer (aucune dépendance UI)
        val result = runCatching {
            RoutePersister.persistRoute(
                noteRepo = noteRepo,
                blocksRepo = blocksRepo,
                gson = routeGson,
                noteId = targetNoteId,
                payload = payload,
                mirrorText = mirrorText
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
            if (result != null) {
                Log.d(TAG, "persistRoute() OK")
                nm.notify(NOTIF_ID, buildNotif(getString(R.string.route_notif_done), ongoing = false))
            } else {
                Log.w(TAG, "persistRoute() NOT saved")
                nm.notify(NOTIF_ID, buildNotif(getString(R.string.route_notif_done_empty), ongoing = false))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            emitState("STOPPED")
        }
    }

    private fun formatTime(ts: Long): String {
        val h = java.text.SimpleDateFormat("HH:mm")
        return h.format(java.util.Date(ts))
    }

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
