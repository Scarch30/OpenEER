package com.example.openeer.ui.library

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.View
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.databinding.FragmentMapBinding
import com.example.openeer.route.RouteRecordingService
import java.util.WeakHashMap
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Tracé & rendu
import com.example.openeer.map.RouteSimplifier
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapRenderers
import com.example.openeer.ui.map.MapText
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.map.RoutePersistResult
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.sheets.MapSnapshotSheet

private const val RTAG = "RouteUI"

/* -------------------------------------------------------------------------------------------------
   État UI lié à l’instance de MapFragment (sans toucher à sa classe).
-------------------------------------------------------------------------------------------------- */

private data class RouteUiState(
    var isRunning: Boolean = false,
    var count: Int = 0
)

private val routeStateCache = WeakHashMap<MapFragment, RouteUiState>()
private fun MapFragment.state(): RouteUiState =
    routeStateCache.getOrPut(this) { RouteUiState() }

/* -------------------------------------------------------------------------------------------------
   Tracé live : points accumulés pendant l’enregistrement
-------------------------------------------------------------------------------------------------- */
private val liveRoutePoints = mutableListOf<LatLng>()

/* -------------------------------------------------------------------------------------------------
   Intégration – à appeler depuis MapFragment.onViewCreated(...)
-------------------------------------------------------------------------------------------------- */

fun MapFragment.setupRouteUiBindings() {
    if (isPickMode) {
        Log.d(RTAG, "setupRouteUiBindings(): skipped in pick mode")
        return
    }
    val s = state()
    Log.d(RTAG, "setupRouteUiBindings(): state=$s")

    // Bouton principal (GPS)
    b.btnRecordRoute.setOnClickListener {
        Log.d(RTAG, "btnRecordRoute CLICK (isRunning=${s.isRunning}, manual=${isManualRouteModeSafe()})")
        onRouteButtonClicked()
    }

    // Annuler tracé manuel si ce bouton existe dans ton layout
    b.btnCancelManualRoute?.setOnClickListener {
        Log.d(RTAG, "btnCancelManualRoute CLICK → cancelManualRouteDrawingSafe()")
        cancelManualRouteDrawingSafe()
    }

    // Label initial
    updateRouteUi()

    // Receivers lifecycle-scopés à la vue
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        private var stateReceiverRegistered = false
        private var pointsReceiverRegistered = false
        private var savedReceiverRegistered  = false

        private val routeStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val st = intent.getStringExtra(RouteRecordingService.EXTRA_STATE)
                Log.d(RTAG, "ACTION_STATE received: state=$st")
                when (st) {
                    "STARTED" -> {
                        s.isRunning = true
                        s.count = 0
                        liveRoutePoints.clear()
                        recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)
                        RouteDebugOverlay.hide(this@setupRouteUiBindings)
                        updateRouteUi()
                    }
                    "STOPPED" -> {
                        s.isRunning = false
                        s.count = 0
                        liveRoutePoints.clear()
                        recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)
                        updateRouteUi()
                        // Le snapshot sera déclenché par ACTION_SAVED si la persistance a réussi
                    }
                }
            }
        }

        private val routePointsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newCount = intent.getIntExtra(RouteRecordingService.EXTRA_COUNT, s.count)
                s.count = newCount

                // Coords live (pour tracer la polyline pendant la course)
                if (intent.hasExtra(RouteRecordingService.EXTRA_LAST_LAT) &&
                    intent.hasExtra(RouteRecordingService.EXTRA_LAST_LON)
                ) {
                    val lat = intent.getDoubleExtra(RouteRecordingService.EXTRA_LAST_LAT, Double.NaN)
                    val lon = intent.getDoubleExtra(RouteRecordingService.EXTRA_LAST_LON, Double.NaN)
                    val ts  = intent.getLongExtra(RouteRecordingService.EXTRA_LAST_TS, System.currentTimeMillis())
                    if (!lat.isNaN() && !lon.isNaN()) {
                        liveRoutePoints.add(LatLng(lat, lon))
                        // Convertit en RoutePointPayload pour le LineManager
                        val pts = liveRoutePoints.mapIndexed { idx, ll ->
                            // On réutilise les timestamps si dispo (ts pour le dernier, sinon approx)
                            val t = if (idx == liveRoutePoints.lastIndex) ts else System.currentTimeMillis()
                            RoutePointPayload(ll.latitude, ll.longitude, t)
                        }
                        recordingRouteLine = MapPolylines.updateRoutePolyline(
                            polylineManager,
                            recordingRouteLine,
                            pts
                        )
                    }
                }
                updateRouteUi()
            }
        }

        private val routeSavedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val noteId = intent.getLongExtra(RouteRecordingService.EXTRA_NOTE_ID, 0L)
                val routeBlockId = intent.getLongExtra(RouteRecordingService.EXTRA_ROUTE_BLOCK_ID, 0L)
                val mirrorBlockId = intent.getLongExtra(RouteRecordingService.EXTRA_MIRROR_BLOCK_ID, 0L)
                val count = intent.getIntExtra(RouteRecordingService.EXTRA_COUNT, 0)
                Log.d(RTAG, "ACTION_SAVED: note=$noteId routeBlock=$routeBlockId mirror=$mirrorBlockId count=$count")

                // Efface le tracé live
                liveRoutePoints.clear()
                recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)

                // Recharge la liste de notes/blocs
                refreshNotesAsync()

                // Reconstruire le payload pour le snapshot à partir du bloc routeJson
                viewLifecycleOwner.lifecycleScope.launch {
                    val payload: RoutePayload? = withContext(Dispatchers.IO) {
                        val block = blocksRepo.getBlock(routeBlockId)
                        val json = block?.routeJson
                        json?.let { runCatching { routeGson.fromJson(it, RoutePayload::class.java) }.getOrNull() }
                    }

                    if (payload != null && payload.points.isNotEmpty()) {
                        val adaptiveEpsilon = RouteSimplifier.adaptiveEpsilonMeters(payload.points)
                        val simplified = RouteSimplifier.simplifyMeters(payload.points, adaptiveEpsilon)
                        val previewPoints = if (simplified.size >= 2) simplified else payload.points

                        this@setupRouteUiBindings.maybeUpdateRouteDebugOverlay(payload.points)

                        // Recadrer la carte sur la route puis capturer le snapshot
                        MapRenderers.fitToRoute(map, previewPoints, requireContext())

                        val result = RoutePersistResult(
                            noteId = noteId,
                            routeBlockId = routeBlockId,
                            mirrorBlockId = mirrorBlockId,
                            payload = payload
                        )
                        // Reprend ton flux normal de capture
                        captureRoutePreview(result) {
                            val lifecycle = viewLifecycleOwner.lifecycle
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                MapSnapshotSheet.show(parentFragmentManager, routeBlockId)
                                Log.d(RTAG, "RouteUI: opened snapshot sheet for route $routeBlockId")
                            }
                        }

                        targetNoteId = noteId
                        onTargetNoteIdChanged(noteId)
                        val firstPoint = payload.points.firstOrNull()
                        if (firstPoint != null) {
                            setTargetNoteLocation(firstPoint.lat, firstPoint.lon)
                        }
                        showHint(getString(R.string.map_route_saved))
                    } else {
                        // Pas de payload récupéré : on notifie quand même, et la liste est rafraîchie
                        targetNoteId = noteId
                        onTargetNoteIdChanged(noteId)
                        showHint(getString(R.string.map_route_saved))
                        RouteDebugOverlay.hide(this@setupRouteUiBindings)
                    }

                    updateRouteUi()
                }
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            val ctx = requireContext()
            Log.d(RTAG, "onStart() → register receivers")

            ContextCompat.registerReceiver(
                ctx,
                routeStateReceiver,
                IntentFilter(RouteRecordingService.ACTION_STATE),
                RECEIVER_NOT_EXPORTED
            )
            stateReceiverRegistered = true

            ContextCompat.registerReceiver(
                ctx,
                routePointsReceiver,
                IntentFilter(RouteRecordingService.ACTION_POINTS),
                RECEIVER_NOT_EXPORTED
            )
            pointsReceiverRegistered = true

            ContextCompat.registerReceiver(
                ctx,
                routeSavedReceiver,
                IntentFilter(RouteRecordingService.ACTION_SAVED),
                RECEIVER_NOT_EXPORTED
            )
            savedReceiverRegistered = true
        }

        override fun onStop(owner: LifecycleOwner) {
            val ctx = requireContext()
            Log.d(RTAG, "onStop() → unregister receivers")
            if (stateReceiverRegistered) {
                runCatching { ctx.unregisterReceiver(routeStateReceiver) }
                stateReceiverRegistered = false
            }
            if (pointsReceiverRegistered) {
                runCatching { ctx.unregisterReceiver(routePointsReceiver) }
                pointsReceiverRegistered = false
            }
            if (savedReceiverRegistered) {
                runCatching { ctx.unregisterReceiver(routeSavedReceiver) }
                savedReceiverRegistered = false
            }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            RouteDebugOverlay.hide(this@setupRouteUiBindings)
        }
    })
}

/* -------------------------------------------------------------------------------------------------
   Logique UI – bouton principal
-------------------------------------------------------------------------------------------------- */

/**
 * 1) Si enregistrement en cours → STOP service
 * 2) Si mode manuel actif → saveManualRoute()
 * 3) Sinon → START service (GPS)
 */
internal fun MapFragment.onRouteButtonClicked() {
    if (isPickMode) {
        Log.d(RTAG, "onRouteButtonClicked(): ignored in pick mode")
        return
    }
    val s = state()
    when {
        s.isRunning -> {
            Log.d(RTAG, "onRouteButtonClicked(): RUNNING → stop service")
            RouteRecordingService.stop(requireContext())
        }
        isManualRouteModeSafe() -> {
            Log.d(RTAG, "onRouteButtonClicked(): manual mode → saveManualRouteSafe()")
            saveManualRouteSafe()
        }
        else -> {
            Log.d(RTAG, "onRouteButtonClicked(): idle → startRouteRecordingViaService()")
            startRouteRecordingViaService()
        }
    }
}

private fun MapFragment.updateRouteUi() {
    val s = state()
    Log.d(RTAG, "updateRouteUi(): isRunning=${s.isRunning} count=${s.count}")

    if (!shouldShowLocationActions) {
        b.locationActions.isVisible = false
        return
    }

    if (isManualRouteModeSafe()) {
        runCatching {
            val manualUi = this::class.java.getDeclaredMethod("updateManualRouteUi")
            manualUi.isAccessible = true
            manualUi.invoke(this)
        }
        return
    }

    b.btnRecordRoute.isVisible = true
    b.btnRecordRoute.isEnabled = true

    if (s.isRunning) {
        b.btnRecordRoute.text = getString(R.string.map_route_stop_with_count, s.count)
        b.btnRecordRoute.contentDescription = b.btnRecordRoute.text
        b.btnCancelManualRoute?.isEnabled = false
    } else {
        b.btnRecordRoute.text = getString(R.string.map_route_start_gps)
        b.btnRecordRoute.contentDescription = getString(R.string.map_route_start_cd)
        b.btnCancelManualRoute?.isEnabled = true
    }
}

/* -------------------------------------------------------------------------------------------------
   Démarrage service – permissions & intent
-------------------------------------------------------------------------------------------------- */

private fun MapFragment.startRouteRecordingViaService() {
    if (isPickMode) {
        Log.d(RTAG, "startRouteRecordingViaService(): ignored in pick mode")
        return
    }
    Log.d(RTAG, "startRouteRecordingViaService()")
    if (!hasFineLocationPermission()) {
        Log.d(RTAG, "→ missing location permission → hint + return")
        requestLocationPermissionHint()
        return
    }

    val noteId = currentTargetNoteId()
    Log.d(RTAG, "→ RouteRecordingService.start(noteId=$noteId)")
    RouteRecordingService.start(requireContext(), noteId)

    // petit feedback
    showHintSafe(getString(R.string.route_notif_running, 0))
}

/* -------------------------------------------------------------------------------------------------
   Helpers vers la logique existante du MapFragment (binding, réflexion minimale)
-------------------------------------------------------------------------------------------------- */

// Accès binding – utilise la propriété exposée par MapFragment
private val MapFragment.b: FragmentMapBinding
    get() = this.binding

/** Retourne l’ID de note cible si dispo, sinon 0L (création à la persistance) */
private fun MapFragment.currentTargetNoteId(): Long {
    return try {
        val f = this::class.java.getDeclaredField("targetNoteId")
        f.isAccessible = true
        (f.get(this) as? Long) ?: 0L
    } catch (_: Throwable) {
        0L
    }
}

private fun MapFragment.isManualRouteModeSafe(): Boolean {
    return try {
        val f = this::class.java.getDeclaredField("isManualRouteMode")
        f.isAccessible = true
        (f.get(this) as? Boolean) == true
    } catch (_: Throwable) {
        false
    }
}

private fun MapFragment.saveManualRouteSafe() {
    runCatching {
        val m = this::class.java.getDeclaredMethod("saveManualRoute")
        m.isAccessible = true
        m.invoke(this)
    }
}

fun MapFragment.cancelManualRouteDrawingSafe() {
    runCatching {
        val m = this::class.java.getDeclaredMethod("finishManualRouteDrawing")
        m.isAccessible = true
        m.invoke(this)
    }
}

private fun MapFragment.showHintSafe(msg: String) {
    runCatching {
        val m = this::class.java.getDeclaredMethod("showHint", String::class.java)
        m.isAccessible = true
        m.invoke(this, msg)
    }
}

/* -------------------------------------------------------------------------------------------------
   Permissions – garde-fous minimaux
-------------------------------------------------------------------------------------------------- */

private fun MapFragment.hasFineLocationPermission(): Boolean {
    val ctx = requireContext()
    val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private fun MapFragment.requestLocationPermissionHint() {
    showHintSafe(getString(R.string.map_hint_location_perm_needed))
}

/* -------------------------------------------------------------------------------------------------
   Extensions utilitaires UI
-------------------------------------------------------------------------------------------------- */

private var FragmentMapBinding?.isVisibleCompat: Boolean
    get() = (this?.root?.visibility == View.VISIBLE)
    set(value) { this?.root?.visibility = if (value) View.VISIBLE else View.GONE }

/* -------------------------------------------------------------------------------------------------
   WRAPPERS de compatibilité (résolvent les références encore présentes dans MapFragment.kt)
-------------------------------------------------------------------------------------------------- */

/** Ancien entrypoint → démarre désormais le ForegroundService */
internal fun MapFragment.startRouteRecording() {
    Log.d(RTAG, "startRouteRecording() wrapper")
    if (isPickMode) {
        Log.d(RTAG, "startRouteRecording(): ignored in pick mode")
        return
    }
    startRouteRecordingViaService()
}

/** Ancien stop → stoppe le service ; l’UI sera mise à jour via les broadcasts */
internal fun MapFragment.cancelRouteRecording() {
    Log.d(RTAG, "cancelRouteRecording() wrapper")
    if (isPickMode) {
        Log.d(RTAG, "cancelRouteRecording(): ignored in pick mode")
        return
    }
    RouteRecordingService.stop(requireContext())
}

internal fun MapFragment.refreshRouteButtonState() {
    updateRouteUi()
}
