package com.example.openeer.ui.map

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.example.openeer.ui.library.MapSnapDiag // logs + Ticker
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Helpers de capture de snapshots MapLibre.
 * - captureVisibleViewport : capture immédiate de ce qui est à l'écran (aucun mouvement de caméra).
 * - captureMapSnapshot     : si center/bounds fournis, recadre et attend l'IDLE, sinon capture immédiate.
 * - snapshotAfterMoveFullyRendered : moveCamera (sans anim), attend une frame "fully rendered" côté MapView, puis snapshot.
 */
object MapSnapshots {

    /**
     * Capture *immédiate* du viewport courant.
     * Ne touche pas à la caméra. Renvoie exactement ce que l'utilisateur voit.
     */
    fun captureVisibleViewport(
        map: MapLibreMap?,
        onBitmap: (Bitmap?) -> Unit
    ) {
        val mapObj = map ?: return onBitmap(null)
        mapObj.snapshot { bmp -> onBitmap(bmp) }
    }

    /**
     * Déplace la caméra SANS ANIMATION (moveCamera), puis déclenche le snapshot
     * après la première frame "fully rendered" signalée par MapView.
     * Si le signal n'arrive pas dans [timeoutMs], on snapshot quand même (meilleur effort).
     */
    fun snapshotAfterMoveFullyRendered(
        mapView: MapView?,
        map: MapLibreMap?,
        center: LatLng,
        zoom: Double?,
        timeoutMs: Long = 1200L,
        onBitmap: (Bitmap?) -> Unit
    ) {
        val mView = mapView ?: return onBitmap(null)
        val m = map ?: return onBitmap(null)

        val tick = MapSnapDiag.Ticker()
        MapSnapDiag.trace { "SNAPCore: moveCamera begin center=$center zoom=$zoom" }
        if (zoom != null) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
        } else {
            m.moveCamera(CameraUpdateFactory.newLatLng(center))
        }
        MapSnapDiag.trace { "SNAPCore: moveCamera end (+${tick.ms()} ms)" }

        val handler = Handler(Looper.getMainLooper())

        lateinit var timeoutRunnable: Runnable
        lateinit var frameListener: MapView.OnDidFinishRenderingFrameListener

        fun doSnapshot(reason: String) {
            MapSnapDiag.trace { "SNAPCore: snapshot() reason=$reason @${tick.ms()} ms" }
            m.snapshot { bmp ->
                MapSnapDiag.trace { "SNAPCore: snapshot() returned bmp=${bmp?.width}x${bmp?.height} after ${tick.ms()} ms" }
                onBitmap(bmp)
            }
        }

        fun cleanup() {
            runCatching { mView.removeOnDidFinishRenderingFrameListener(frameListener) }
            handler.removeCallbacks(timeoutRunnable)
        }

        timeoutRunnable = Runnable {
            MapSnapDiag.trace { "SNAPCore: TIMEOUT waiting fully-rendered after ${tick.ms()} ms" }
            cleanup()
            doSnapshot("timeout")
        }

        // Version 3-args (fully, renderTime, frameRate)
        frameListener = MapView.OnDidFinishRenderingFrameListener { fully, renderTime, frameRate ->
            MapSnapDiag.trace {
                "SNAPCore: frame listener fully=$fully renderTime=${"%.2f".format(renderTime)}ms fps=${"%.1f".format(frameRate)} @${tick.ms()} ms"
            }
            if (fully) {
                cleanup()
                doSnapshot("fully-rendered")
            }
        }

        handler.postDelayed(timeoutRunnable, timeoutMs)
        mView.addOnDidFinishRenderingFrameListener(frameListener)
        MapSnapDiag.trace { "SNAPCore: listener attached (timeout=${timeoutMs}ms)" }
    }

    /**
     * Capture avec recadrage optionnel.
     * - Si `center` et `bounds` sont null -> capture immédiate (pas d'IDLE, pas d'anim).
     * - Sinon -> anime la caméra puis attend l'IDLE avant de capturer.
     *   (Chemin conservé pour la route; on le revisitera plus tard.)
     */
    fun captureMapSnapshot(
        map: MapLibreMap?,
        center: LatLng? = null,
        bounds: LatLngBounds? = null,
        onBitmap: (Bitmap?) -> Unit
    ) {
        val mapObj = map ?: return onBitmap(null)

        if (center == null && bounds == null) {
            mapObj.snapshot { bmp -> onBitmap(bmp) }
            return
        }

        // Attente "idle" (héritage) — moins fiable pour les labels, mais gardé pour la route.
        captureMapSnapshotAfterIdle(mapObj, onBitmap)

        when {
            bounds != null -> {
                mapObj.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
            }
            center != null -> {
                mapObj.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 15.0))
            }
        }
    }

    /**
     * Héritage (route) : attend l'IDLE (ou timeout) puis fait un snapshot.
     */
    fun captureMapSnapshotAfterIdle(
        map: MapLibreMap,
        onResult: (Bitmap?) -> Unit,
        timeoutMs: Long = 1500L
    ) {
        val handler = Handler(Looper.getMainLooper())

        lateinit var timeoutRunnable: Runnable
        lateinit var idleListener: MapLibreMap.OnCameraIdleListener

        timeoutRunnable = Runnable {
            runCatching { map.removeOnCameraIdleListener(idleListener) }
            onResult(null)
        }

        idleListener = MapLibreMap.OnCameraIdleListener {
            map.removeOnCameraIdleListener(idleListener)
            handler.removeCallbacks(timeoutRunnable)
            map.snapshot { bmp -> onResult(bmp) }
        }

        handler.postDelayed(timeoutRunnable, timeoutMs)
        map.addOnCameraIdleListener(idleListener)
        // pas de nudge ici non plus
    }
}
