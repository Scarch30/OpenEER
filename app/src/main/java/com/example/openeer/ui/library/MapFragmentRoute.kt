package com.example.openeer.ui.library

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.openeer.R
import com.example.openeer.databinding.FragmentMapBinding
import com.example.openeer.route.RouteRecordingService
import java.util.WeakHashMap

/**
 * Module "Route" du MapFragment – version Service.
 *
 * - ne crée plus de RouteRecorder local au Fragment
 * - démarre/arrête RouteRecordingService (foreground, type location)
 * - observe l’état du service (STARTED/STOPPED) et le compteur de points
 * - met à jour l’UI ("Démarrer (GPS)" ↔ "Arrêter (N)")
 *
 * À appeler une fois depuis MapFragment.onViewCreated(...):
 *     setupRouteUiBindings()
 *
 * IMPORTANT :
 * - Ne JAMAIS stopper l’enregistrement dans onPause()/onDestroyView().
 * - Le service persiste l’itinéraire à l’arrêt (ACTION_STOP).
 */

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
   Intégration – à appeler depuis MapFragment
-------------------------------------------------------------------------------------------------- */

fun MapFragment.setupRouteUiBindings() {
    val s = state()
    Log.d("RouteUI", "setupRouteUiBindings(): state=$s")

    // Bouton principal (GPS)
    b.btnRecordRoute.setOnClickListener {
        Log.d("RouteUI", "btnRecordRoute CLICK (isRunning=${s.isRunning}, manual=${isManualRouteModeSafe()}, savingRoute? refl handled)")
        onRouteButtonClicked()
    }

    // Annuler tracé manuel si ce bouton existe déjà dans ton layout
    b.btnCancelManualRoute?.setOnClickListener {
        Log.d("RouteUI", "btnCancelManualRoute CLICK → cancelManualRouteDrawingSafe()")
        cancelManualRouteDrawingSafe()
    }

    // Label initial
    updateRouteUi()

    // Enregistrer/retirer les receivers aux bonnes étapes du cycle de VUE
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        private var stateReceiverRegistered = false
        private var pointsReceiverRegistered = false

        private val routeStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val st = intent.getStringExtra(RouteRecordingService.EXTRA_STATE)
                Log.d("RouteUI", "Broadcast ACTION_STATE received: state=$st")
                when (st) {
                    "STARTED" -> {
                        s.isRunning = true
                        updateRouteUi()
                    }
                    "STOPPED" -> {
                        s.isRunning = false
                        s.count = 0
                        updateRouteUi()
                        Log.d("RouteUI", "State STOPPED → refreshNotesAsync()")
                        // Recharge notes/traces depuis DB pour afficher la polyline finale
                        refreshNotesAsync()
                    }
                    else -> Log.d("RouteUI", "Unknown state in ACTION_STATE: $st")
                }
            }
        }

        private val routePointsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newCount = intent.getIntExtra(RouteRecordingService.EXTRA_COUNT, s.count)
                Log.d("RouteUI", "Broadcast ACTION_POINTS received: count=$newCount")
                s.count = newCount
                updateRouteUi()
                // Option: tracé live → diffuser les points et rafraîchir MapPolylines ici.
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            val ctx = requireContext()
            Log.d("RouteUI", "onStart() → register receivers")

            ContextCompat.registerReceiver(
                ctx,
                routeStateReceiver,
                IntentFilter(RouteRecordingService.ACTION_STATE),
                RECEIVER_NOT_EXPORTED
            )?.also { stateReceiverRegistered = true }

            ContextCompat.registerReceiver(
                ctx,
                routePointsReceiver,
                IntentFilter(RouteRecordingService.ACTION_POINTS),
                RECEIVER_NOT_EXPORTED
            )?.also { pointsReceiverRegistered = true }

            Log.d("RouteUI", "Receivers registered: state=$stateReceiverRegistered points=$pointsReceiverRegistered")
        }

        override fun onStop(owner: LifecycleOwner) {
            val ctx = requireContext()
            Log.d("RouteUI", "onStop() → unregister receivers")
            if (stateReceiverRegistered) {
                runCatching { ctx.unregisterReceiver(routeStateReceiver) }
                stateReceiverRegistered = false
            }
            if (pointsReceiverRegistered) {
                runCatching { ctx.unregisterReceiver(routePointsReceiver) }
                pointsReceiverRegistered = false
            }
            Log.d("RouteUI", "Receivers unregistered.")
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
    val s = state()
    when {
        s.isRunning -> {
            Log.d("RouteUI", "onRouteButtonClicked(): currently RUNNING → stop service")
            RouteRecordingService.stop(requireContext())
        }
        isManualRouteModeSafe() -> {
            Log.d("RouteUI", "onRouteButtonClicked(): manual mode → saveManualRouteSafe()")
            saveManualRouteSafe()
        }
        else -> {
            Log.d("RouteUI", "onRouteButtonClicked(): idle → startRouteRecordingViaService()")
            startRouteRecordingViaService()
        }
    }
}

private fun MapFragment.updateRouteUi() {
    val s = state()
    Log.d("RouteUI", "updateRouteUi(): isRunning=${s.isRunning} count=${s.count}")
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
    Log.d("RouteUI", "startRouteRecordingViaService()")
    if (!hasFineLocationPermission()) {
        Log.d("RouteUI", "→ missing location permission → hint + return")
        requestLocationPermissionHint()
        return
    }

    val noteId = currentTargetNoteId()
    Log.d("RouteUI", "→ calling RouteRecordingService.start(noteId=$noteId)")
    RouteRecordingService.start(requireContext(), noteId)

    // Petit feedback
    showHintSafe(getString(R.string.route_notif_running, 0))
}

/* -------------------------------------------------------------------------------------------------
   Helpers vers la logique existante du MapFragment
-------------------------------------------------------------------------------------------------- */

// Accès binding – utilise la propriété exposée par MapFragment (pas de réflexion)
private val MapFragment.b: FragmentMapBinding
    get() = this.binding

/** Retourne l’ID de note cible si dispo, sinon 0L (création à la persistance) */
private fun MapFragment.currentTargetNoteId(): Long {
    return try {
        val f = this::class.java.getDeclaredField("targetNoteId")
        f.isAccessible = true
        val v = (f.get(this) as? Long) ?: 0L
        Log.d("RouteUI", "currentTargetNoteId(): $v")
        v
    } catch (t: Throwable) {
        Log.w("RouteUI", "currentTargetNoteId(): reflection failed: ${t.message}")
        0L
    }
}

private fun MapFragment.isManualRouteModeSafe(): Boolean {
    return try {
        val f = this::class.java.getDeclaredField("isManualRouteMode")
        f.isAccessible = true
        val v = (f.get(this) as? Boolean) == true
        Log.d("RouteUI", "isManualRouteModeSafe(): $v")
        v
    } catch (t: Throwable) {
        Log.w("RouteUI", "isManualRouteModeSafe(): reflection failed: ${t.message}")
        false
    }
}

private fun MapFragment.saveManualRouteSafe() {
    runCatching {
        val m = this::class.java.getDeclaredMethod("saveManualRoute")
        m.isAccessible = true
        Log.d("RouteUI", "saveManualRouteSafe(): invoking")
        m.invoke(this)
    }.onFailure { t ->
        Log.e("RouteUI", "saveManualRouteSafe(): reflection failed", t)
    }
}

fun MapFragment.cancelManualRouteDrawingSafe() {
    runCatching {
        val m = this::class.java.getDeclaredMethod("finishManualRouteDrawing")
        m.isAccessible = true
        Log.d("RouteUI", "cancelManualRouteDrawingSafe(): invoking")
        m.invoke(this)
    }.onFailure { t ->
        Log.e("RouteUI", "cancelManualRouteDrawingSafe(): reflection failed", t)
    }
}

private fun MapFragment.showHintSafe(msg: String) {
    runCatching {
        val m = this::class.java.getDeclaredMethod("showHint", String::class.java)
        m.isAccessible = true
        Log.d("RouteUI", "showHintSafe(): \"$msg\"")
        m.invoke(this, msg)
    }.onFailure { t ->
        Log.e("RouteUI", "showHintSafe(): reflection failed", t)
    }
}

// ⚠️ Pas de "private fun refreshNotesAsync()" ici : on utilise la version déjà définie
// dans MapFragmentNotes.kt (internal fun MapFragment.refreshNotesAsync()).

/* -------------------------------------------------------------------------------------------------
   Permissions – garde-fous minimaux (on s’appuie sur ta routine existante pour la demande)
-------------------------------------------------------------------------------------------------- */

private fun MapFragment.hasFineLocationPermission(): Boolean {
    val ctx = requireContext()
    val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    Log.d("RouteUI", "hasFineLocationPermission(): fine=$fine coarse=$coarse")
    return fine || coarse
}

private fun MapFragment.requestLocationPermissionHint() {
    Log.d("RouteUI", "requestLocationPermissionHint() → showHintSafe()")
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
    Log.d("RouteUI", "startRouteRecording() wrapper → startRouteRecordingViaService()")
    startRouteRecordingViaService()
}

/** Ancien stop → stoppe le service ; l’UI sera mise à jour via le broadcast "STOPPED" */
internal fun MapFragment.cancelRouteRecording() {
    Log.d("RouteUI", "cancelRouteRecording() wrapper → RouteRecordingService.stop()")
    RouteRecordingService.stop(requireContext())
}

/** Appels liés au mode manuel (si présents, on les route ; sinon no-op propre) */
internal fun MapFragment.onManualRouteUndoClicked() = runCatching {
    val m = this::class.java.getDeclaredMethod("onManualRouteUndoClicked")
    m.isAccessible = true
    Log.d("RouteUI", "onManualRouteUndoClicked() wrapper invoke")
    m.invoke(this)
}.getOrElse { Log.w("RouteUI", "onManualRouteUndoClicked(): reflection missing") }

internal fun MapFragment.refreshRouteButtonState() = runCatching {
    val m = this::class.java.getDeclaredMethod("updateRouteUi")
    m.isAccessible = true
    Log.d("RouteUI", "refreshRouteButtonState() wrapper → updateRouteUi()")
    m.invoke(this)
}.getOrElse { Log.w("RouteUI", "refreshRouteButtonState(): reflection missing") }

internal fun MapFragment.startManualRouteDrawing() = runCatching {
    val m = this::class.java.getDeclaredMethod("startManualRouteDrawing")
    m.isAccessible = true
    Log.d("RouteUI", "startManualRouteDrawing() wrapper invoke")
    m.invoke(this)
}.getOrElse { Log.w("RouteUI", "startManualRouteDrawing(): reflection missing") }

internal fun MapFragment.handleManualMapTap() = runCatching {
    val m = this::class.java.getDeclaredMethod("handleManualMapTap")
    m.isAccessible = true
    Log.d("RouteUI", "handleManualMapTap() wrapper invoke")
    m.invoke(this)
}.getOrElse { Log.w("RouteUI", "handleManualMapTap(): reflection missing") }
