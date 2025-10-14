package com.example.openeer.ui.library

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.Attachment
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.map.MapSnapshots
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.map.RoutePersistResult
import kotlinx.coroutines.*
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.style.layers.TransitionOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val SNAPSHOT_TIMEOUT_MS = 4000L // 4 secondes max
private const val HERE_TARGET_ZOOM = 16.0    // zoom cible un peu reculé pour stabiliser les labels

internal fun MapFragment.captureLocationPreview(noteId: Long, blockId: Long, lat: Double, lon: Double) {
    if (noteId <= 0) return
    val lifecycle = viewLifecycleOwner.lifecycle
    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

    val tick = MapSnapDiag.Ticker()
    val center = LatLng(lat, lon)
    MapSnapDiag.trace { "SNAP: start (note=$noteId block=$blockId) center=$lat,$lon" }

    setSnapshotInProgress(true)

    val job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
        try {
            withTimeout(SNAPSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    // Transition off pour limiter les fades
                    map?.getStyle { style ->
                        MapSnapDiag.trace { "SNAP: style loaded? transition=${style.transition}" }
                        style.transition = TransitionOptions(0, 0, false)
                    }

                    val currentZoom = runCatching { map?.cameraPosition?.zoom ?: Double.NaN }.getOrDefault(Double.NaN)
                    MapSnapDiag.trace { "SNAP: moveCamera to center (currentZoom=$currentZoom -> target=$HERE_TARGET_ZOOM)" }

                    MapSnapshots.snapshotAfterMoveFullyRendered(
                        mapView = binding.mapView,
                        map = map,
                        center = center,
                        zoom = HERE_TARGET_ZOOM,
                        timeoutMs = 1200L
                    ) { bitmap ->
                        MapSnapDiag.trace {
                            "SNAP: bitmap callback after ${tick.ms()} ms (bitmap=${bitmap?.width}x${bitmap?.height})"
                        }
                        try {
                            if (bitmap != null) {
                                val tPersist = MapSnapDiag.Ticker()
                                persistBlockPreview(noteId, blockId, BlockType.LOCATION, bitmap)
                                MapSnapDiag.trace { "SNAP: persist scheduled (IO off main) took ~${tPersist.ms()} ms to schedule" }
                                showPreviewSavedToast()
                            }
                        } finally {
                            MapSnapDiag.trace { "SNAP: end total=${tick.ms()} ms" }
                            cont.resume(Unit, null)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            MapSnapDiag.trace { "SNAP: TIMEOUT after ${tick.ms()} ms" }
            Log.w(MapFragment.TAG, "Snapshot timeout (LOCATION block=$blockId)")
        } catch (e: CancellationException) {
            MapSnapDiag.trace { "SNAP: CANCEL after ${tick.ms()} ms" }
        } finally {
            setSnapshotInProgress(false)
        }
    }
}

internal fun MapFragment.captureRoutePreview(
    result: RoutePersistResult,
    onComplete: (() -> Unit)? = null
) {
    val noteId = result.noteId
    if (noteId <= 0) {
        onComplete?.invoke(); return
    }
    val points = result.payload.points
    if (points.isEmpty()) {
        onComplete?.invoke(); return
    }

    val latLngs = points.map { LatLng(it.lat, it.lon) }
    val bounds = if (latLngs.size >= 2) {
        val builder = LatLngBounds.Builder()
        latLngs.forEach { builder.include(it) }
        runCatching { builder.build() }.getOrNull()
    } else null

    val center = latLngs.firstOrNull()
    val manager = polylineManager
    var tempLine: Line? = null

    if (manager != null && latLngs.size >= 2) {
        val options = LineOptions()
            .withLatLngs(latLngs)
            .withLineColor(MapUiDefaults.ROUTE_LINE_COLOR)
            .withLineWidth(MapUiDefaults.ROUTE_LINE_WIDTH)
        tempLine = runCatching { manager.create(options as LineOptions) }.getOrNull()
    }

    setSnapshotInProgress(true)

    val job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
        try {
            withTimeout(SNAPSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    MapSnapshots.captureMapSnapshot(
                        map = map,
                        center = if (bounds == null) center else null,
                        bounds = bounds
                    ) { bitmap ->
                        try {
                            tempLine?.let { line -> runCatching { manager?.delete(line) } }
                            if (bitmap != null) {
                                persistBlockPreview(noteId, result.routeBlockId, BlockType.ROUTE, bitmap)
                                showPreviewSavedToast()
                            }
                        } finally {
                            cont.resume(Unit, null)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(MapFragment.TAG, "Snapshot timeout (ROUTE block=${result.routeBlockId})")
        } catch (e: CancellationException) {
            Log.i(MapFragment.TAG, "Snapshot cancelled (ROUTE block=${result.routeBlockId})")
        } finally {
            setSnapshotInProgress(false)
            onComplete?.invoke()
        }
    }
}

/**
 * Sauvegarde sécurisée du bitmap (écriture atomique + suppression ancienne version) + instrumentation.
 */
internal fun MapFragment.persistBlockPreview(noteId: Long, blockId: Long, type: BlockType, bitmap: Bitmap) {
    val appContext = context?.applicationContext ?: return
    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
        val t = MapSnapDiag.Ticker()
        val file = MapPreviewStorage.fileFor(appContext, blockId, type)
        val tmp = File(file.parentFile, "${file.name}.tmp")

        MapSnapDiag.trace { "PERSIST: start file=${file.absolutePath}" }

        val result = runCatching {
            file.parentFile?.mkdirs()
            if (tmp.exists()) tmp.delete()

            FileOutputStream(tmp).use { out ->
                val tCompress = MapSnapDiag.Ticker()
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                MapSnapDiag.trace { "PERSIST: compress PNG ok=$ok in ${tCompress.ms()} ms" }
                if (!ok) throw IOException("Failed to compress bitmap")
            }

            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException("Failed to rename temp file")

            val tDb = MapSnapDiag.Ticker()
            attachmentDao.deleteByNoteAndPath(noteId, file.absolutePath)
            attachmentDao.insert(
                Attachment(
                    noteId = noteId,
                    type = MapPreviewStorage.ATTACHMENT_TYPE,
                    path = file.absolutePath
                )
            )
            MapSnapDiag.trace { "PERSIST: db ops in ${tDb.ms()} ms" }
        }

        MapSnapDiag.trace { "PERSIST: total ${t.ms()} ms (success=${result.isSuccess})" }

        if (result.isFailure) {
            Log.e(MapFragment.TAG, "Failed to persist map preview for block=$blockId", result.exceptionOrNull())
        }
    }
}
