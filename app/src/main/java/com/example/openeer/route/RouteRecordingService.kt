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
import com.example.openeer.Injection
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

    // --- Profils par vitesse + hystérésis ------------------------------------

    private data class RouteProfile(
        val name: String,
        val minDispM: Float,          // distance mini entre points acceptés
        val minIntervalMs: Long,      // intervalle mini entre points acceptés
        val emaAlpha: Float,          // lissage EMA (poids du point courant)
        val simplifyEpsM: Float,      // epsilon pour simplification (post)
        val maxAccuracyM: Float       // précision max acceptée
    )

    // Seuils m/s : <0.6 marche lente, 0.6–1.4 marche rapide, 1.4–4 vélo,
    // 4–12 voiture ville, 12–20 voie rapide, >=20 autoroute
    private val SPEED_BINS = floatArrayOf(0.6f, 1.4f, 4f, 12f, 20f)

    private val PROFILES = listOf(
        // Marche lente ~0–1.2 m/s
        RouteProfile(
            name         = "walk_slow",
            minDispM     = 4.0f,     // ← 5 → 4  (on accepte des petits déplacements)
            minIntervalMs= 850,      // ← 900 → 850
            emaAlpha     = 0.75f,    // ← 0.85 → 0.75 (un peu plus de lissage)
            simplifyEpsM = 4.0f,     // ← 5 → 4 (préserve les courbes de trottoir)
            maxAccuracyM = 18f       // ← 25 → 18 (rejette les fixes trop flous)
        ),

        // Marche rapide ~1.2–2.2 m/s
        RouteProfile(
            name         = "walk_fast",
            minDispM     = 5f,     // ← 6.5 → 5
            minIntervalMs= 900,      // ← 950 → 900
            emaAlpha     = 0.65f,    // ← 0.78 → 0.65
            simplifyEpsM = 5f,     // ← 6 → 5
            maxAccuracyM = 20f       // ← 25 → 20
        ),

        // Vélo urbain ~2.2–6 m/s
        RouteProfile(
            name         = "bike_urban",
            minDispM     = 7f,       // ← 9 → 7
            minIntervalMs= 1000,     // ← 1100 → 1000
            emaAlpha     = 0.55f,    // ← 0.70 → 0.55
            simplifyEpsM = 6f,       // ok
            maxAccuracyM = 22f       // ← 30 → 22
        ),

        // Voiture ville ~6–16 m/s
        RouteProfile(
            name         = "car_city",
            minDispM     = 12f,      // ← 15 → 12
            minIntervalMs= 1200,     // ← 1300 → 1200
            emaAlpha     = 0.50f,    // ← 0.58 → 0.50
            simplifyEpsM = 10f,      // ← 12 → 10
            maxAccuracyM = 30f       // ok
        ),

        // Périph/Express ~16–27 m/s
        RouteProfile(
            name         = "car_express",
            minDispM     = 18f,      // ← 22 → 18
            minIntervalMs= 1400,     // ← 1500 → 1400
            emaAlpha     = 0.45f,    // ← 0.55 → 0.45
            simplifyEpsM = 14f,      // ← 16 → 14
            maxAccuracyM = 35f       // ok
        ),

        // Autoroute >27 m/s
        RouteProfile(
            name         = "highway",
            minDispM     = 25f,      // ← 28 → 25
            minIntervalMs= 1600,     // ← 1700 → 1600
            emaAlpha     = 0.42f,    // ← 0.52 → 0.42
            simplifyEpsM = 18f,      // ← 20 → 18
            maxAccuracyM = 40f       // ok
        ),
    )


    private val SPEED_HYST = 0.4f // m/s d’hystérésis
    private var lastProfileIdx = 0
    private var speedEma: Float = 0f

    private fun pickProfile(speedMpsRaw: Float): RouteProfile {
        val a = 0.25f
        speedEma = if (speedEma == 0f) speedMpsRaw else (a * speedMpsRaw + (1 - a) * speedEma)

        // Maintien par hystérésis autour de la dernière bande
        val i = lastProfileIdx
        val v = speedEma
        if (i in SPEED_BINS.indices) {
            val low = if (i == 0) Float.NEGATIVE_INFINITY else SPEED_BINS[i - 1]
            val high = SPEED_BINS[i]
            val stayLow  = v < high - SPEED_HYST
            val stayHigh = v > low + SPEED_HYST
            if (stayLow && stayHigh) return PROFILES[i]
        }

        // Sinon, recalcule l’index
        var idx = SPEED_BINS.indexOfFirst { v < it }
        if (idx == -1) idx = SPEED_BINS.size
        lastProfileIdx = idx
        return PROFILES[idx]
    }

    // -------------------------------------------------------------------------

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
        RouteRecordingService.ensureChannel(this)

        val db = AppDatabase.get(applicationContext)
        val noteDao       = db.noteDao()
        val blockDao      = db.blockDao()
        val attachmentDao = db.attachmentDao()
        val blockReadDao  = db.blockReadDao()

        blocksRepo = Injection.provideBlocksRepository(this)
        noteRepo   = NoteRepository(
            applicationContext,
            noteDao,
            attachmentDao,
            blockReadDao,
            blocksRepo,
            db.listItemDao(),
            database = db
        )
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
        speedEma = 0f
        lastProfileIdx = 0

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
        val profile = pickProfile(if (speed < 0f) 0f else speed)

        // Propager les valeurs runtime (utilisées par la simplification & snapshots)
        MapUiDefaults.ROUTE_MIN_INTERVAL_MS     = profile.minIntervalMs
        MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M  = profile.minDispM
        MapUiDefaults.ROUTE_MAX_ACCURACY_M      = profile.maxAccuracyM
        MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M  = profile.simplifyEpsM

        val dt = previous?.let { location.time - lastAcceptedAt }
        val dd = previous?.distanceTo(location)
        Log.d(
            GPS_TAG,
            "accept acc=${location.accuracy} dt=${dt ?: "-"} dd=${dd ?: "-"} v=$speed " +
                    "minDisp=${profile.minDispM} prof=${profile.name}"
        )

        val alpha = profile.emaAlpha.toDouble()
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

    private fun shouldAccept(loc: Location): Boolean {
        val speed = computeSpeed(loc)
        val prof = pickProfile(if (speed < 0f) 0f else speed)
        val minDisp = prof.minDispM
        val minInterval = prof.minIntervalMs
        val maxAcc = prof.maxAccuracyM

        if (loc.isFromMockProvider) {
            Log.d(GPS_TAG, "reject: mock provider v=$speed minDisp=$minDisp")
            return false
        }
        if (!loc.hasAccuracy()) {
            Log.d(GPS_TAG, "reject: no accuracy v=$speed minDisp=$minDisp")
            return false
        }
        if (loc.accuracy > maxAcc) {
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
                Log.d(GPS_TAG, "reject: jitter dd=$dd (<${MapUiDefaults.ROUTE_JITTER_REJECT_M}) v=$speed minDisp=$minDisp")
                return false
            }

            // Coin franc : si gros virage + petit déplacement, on peut quand même accepter
            val bearingDeltaOk = runCatching {
                val bd = kotlin.math.abs((loc.bearing - prev.bearing + 540) % 360 - 180) // delta cap [-180,180]
                bd >= 28f && dd >= 3f
            }.getOrDefault(false)

            val dt = loc.time - lastAcceptedAt
            if (!bearingDeltaOk && dt < minInterval) {
                Log.d(GPS_TAG, "reject: dt=$dt (<$minInterval) v=$speed minDisp=$minDisp")
                return false
            }
            if (!bearingDeltaOk && dd < minDisp) {
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
