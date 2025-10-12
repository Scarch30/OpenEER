package com.example.openeer.ui.map

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Helpers de capture de snapshots MapLibre.
 * Aucune logique métier : juste orchestrer camera → idle → snapshot.
 */
object MapSnapshots {

    fun captureMapSnapshot(
        map: MapLibreMap?,
        center: LatLng? = null,
        bounds: LatLngBounds? = null,
        onBitmap: (Bitmap?) -> Unit
    ) {
        val mapObj = map ?: run {
            onBitmap(null)
            return
        }

        captureMapSnapshotAfterIdle(mapObj, onBitmap)

        when {
            bounds != null -> {
                // padding en pixels fourni par l'appelant (utiliser MapRenderers.dpToPx côté appelant)
                mapObj.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
            }
            center != null -> {
                mapObj.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 15.0))
            }
        }
    }

    fun captureMapSnapshotAfterIdle(
        map: MapLibreMap,
        onResult: (Bitmap?) -> Unit,
        timeoutMs: Long = 1500L
    ) {
        val handler = Handler(Looper.getMainLooper())

        lateinit var timeoutRunnable: Runnable
        lateinit var idleListener: MapLibreMap.OnCameraIdleListener

        timeoutRunnable = Runnable {
            // Timeout : on nettoie et on renvoie null
            runCatching { map.removeOnCameraIdleListener(idleListener) }
            onResult(null)
        }

        idleListener = object : MapLibreMap.OnCameraIdleListener {
            override fun onCameraIdle() {
                // Idle atteint : on capture, on nettoie le timeout puis on retire le listener
                map.removeOnCameraIdleListener(this)
                handler.removeCallbacks(timeoutRunnable)
                map.snapshot { bmp -> onResult(bmp) }
            }
        }

        // Armer le timeout
        handler.postDelayed(timeoutRunnable, timeoutMs)

        // Attendre l’idle, puis snapshot
        map.addOnCameraIdleListener(idleListener)

        // Nudge no-op pour garantir un cycle idle si besoin (ne change pas la vue)
        map.animateCamera(CameraUpdateFactory.zoomBy(0.0))
    }
}
