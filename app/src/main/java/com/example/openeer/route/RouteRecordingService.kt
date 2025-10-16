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
import kotlin.math.max

class RouteRecordingService : Service(), LocationListener {

    companion object {
        private const val TAG = "RouteService"
        private const val GPS_TAG = "RouteGPS"

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
    private var lastAccepted: Location? = null
    private var lastAcceptedAt: Long = 0L
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
        lastAccepted = null
        lastAcceptedAt = 0L

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
        if (points.size >= MapUiDefaults.MAX_ROUTE_POINTS) return
        if (!shouldAccept(location)) return

        val previous = lastAccepted
        val speed = computeSpeed(location)
        val minDisp = minDisplacementFor(speed)
        val dt = previous?.let { location.time - lastAcceptedAt }
        val dd = previous?.distanceTo(location)
        Log.d(
            GPS_TAG,
            "accept acc=${location.accuracy} dt=${dt ?: "-"} dd=${dd ?: "-"} v=$speed minDisp=$minDisp"
        )

        val alpha = when {
            speed > 10f -> MapUiDefaults.ROUTE_EMA_FAST_ALPHA
            speed > 3f -> MapUiDefaults.ROUTE_EMA_MED_ALPHA
            speed >= 0f -> MapUiDefaults.ROUTE_EMA_SLOW_ALPHA
            else -> MapUiDefaults.ROUTE_EMA_MED_ALPHA
        }.toDouble()

        val lat = previous?.let { alpha * location.latitude + (1 - alpha) * it.latitude }
            ?: location.latitude
        val lon = previous?.let { alpha * location.longitude + (1 - alpha) * it.longitude }
            ?: location.longitude

        addPoint(lat, lon, location.time)

        lastAccepted = Location(location).apply {
            latitude = lat
            longitude = lon
        }
        lastAcceptedAt = location.time
    }

    private fun computeSpeed(loc: Location): Float {
        return when {
            loc.hasSpeed() -> loc.speed
            lastAccepted != null -> {
                val dtMs = loc.time - lastAcceptedAt
                if (dtMs <= 0L) return -1f
                val dtSeconds = dtMs / 1_000f
                if (dtSeconds <= 0f) -1f else lastAccepted!!.distanceTo(loc) / dtSeconds
            }
            else -> -1f
        }
    }

    private fun minDisplacementFor(speed: Float): Float {
        val base = MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M
        return when {
            speed < 0f -> max(base, 10f)
            speed <= 1f -> max(base, 6f)
            speed <= 3f -> max(base, 10f)
            else -> base
        }
    }

    private fun shouldAccept(loc: Location): Boolean {
        val speed = computeSpeed(loc)
        val minDisp = minDisplacementFor(speed)
        if (loc.isFromMockProvider) {
            Log.d(GPS_TAG, "reject: mock provider v=$speed minDisp=$minDisp")
            return false
        }
        if (!loc.hasAccuracy()) {
            Log.d(GPS_TAG, "reject: no accuracy v=$speed minDisp=$minDisp")
            return false
        }
        if (loc.accuracy > MapUiDefaults.ROUTE_MAX_ACCURACY_M) {
            Log.d(GPS_TAG, "reject: accuracy=${loc.accuracy} v=$speed minDisp=$minDisp")
            return false
        }

        if (loc.hasSpeed()) {
            val rawSpeed = loc.speed
            if (rawSpeed < 0f || rawSpeed > MapUiDefaults.ROUTE_MAX_SPEED_MPS) {
                Log.d(GPS_TAG, "reject: speed=$rawSpeed v=$speed minDisp=$minDisp")
                return false
            }
        }

        lastAccepted?.let { prev ->
            val dd = loc.distanceTo(prev)
            if (dd < MapUiDefaults.ROUTE_JITTER_REJECT_M) {
                Log.d(
                    GPS_TAG,
                    "reject: jitter dd=$dd (<${MapUiDefaults.ROUTE_JITTER_REJECT_M}) v=$speed minDisp=$minDisp"
                )
                return false
            }

            val dt = loc.time - lastAcceptedAt
            if (dt < MapUiDefaults.ROUTE_MIN_INTERVAL_MS) {
                Log.d(
                    GPS_TAG,
                    "reject: dt=$dt (<${MapUiDefaults.ROUTE_MIN_INTERVAL_MS}) v=$speed minDisp=$minDisp"
                )
                return false
            }
            if (dd < minDisp) {
                Log.d(GPS_TAG, "reject: dd=$dd (<$minDisp) v=$speed minDisp=$minDisp")
                return false
            }
        }

        return true
    }

    private fun addPoint(lat: Double, lon: Double, timestamp: Long) {
        val payload = RoutePointPayload(lat, lon, timestamp)
        points.add(payload)

        val count = pointCount.incrementAndGet()
        Log.d(TAG, "point #$count ${payload.lat},${payload.lon} @${payload.t}")
        updateNotif(count)

        sendBroadcast(
            Intent(ACTION_POINTS)
                .setPackage(packageName)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_LAST_LAT, payload.lat)
                .putExtra(EXTRA_LAST_LON, payload.lon)
                .putExtra(EXTRA_LAST_TS, payload.t)
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
