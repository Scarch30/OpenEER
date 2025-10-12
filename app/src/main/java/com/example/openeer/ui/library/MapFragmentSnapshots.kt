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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val SNAPSHOT_TIMEOUT_MS = 4000L // 4 secondes max

internal fun MapFragment.captureLocationPreview(noteId: Long, blockId: Long, lat: Double, lon: Double) {
    if (noteId <= 0) return
    val center = LatLng(lat, lon)

    // si déjà détruit, ne rien faire
    val lifecycle = viewLifecycleOwner.lifecycle
    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

    setSnapshotInProgress(true)

    val job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
        try {
            withTimeout(SNAPSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    MapSnapshots.captureMapSnapshot(map = map, center = center) { bitmap ->
                        try {
                            if (bitmap != null) {
                                persistBlockPreview(noteId, blockId, BlockType.LOCATION, bitmap)
                                showPreviewSavedToast()
                            }
                        } finally {
                            cont.resume(Unit, null)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(MapFragment.TAG, "Snapshot timeout (LOCATION block=$blockId)")
        } catch (e: CancellationException) {
            Log.i(MapFragment.TAG, "Snapshot cancelled (LOCATION block=$blockId)")
        } finally {
            setSnapshotInProgress(false)
        }
    }

    postSnapshotJob(job)
}

internal fun MapFragment.captureRoutePreview(
    result: RoutePersistResult,
    onComplete: (() -> Unit)? = null
) {
    val noteId = result.noteId
    if (noteId <= 0) {
        onComplete?.invoke()
        return
    }
    val points = result.payload.points
    if (points.isEmpty()) {
        onComplete?.invoke()
        return
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

    postSnapshotJob(job)
}

/**
 * Sauvegarde sécurisée du bitmap (écriture atomique + suppression ancienne version).
 */
internal fun MapFragment.persistBlockPreview(noteId: Long, blockId: Long, type: BlockType, bitmap: Bitmap) {
    val appContext = context?.applicationContext ?: return
    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
        val file = MapPreviewStorage.fileFor(appContext, blockId, type)
        val tmp = File(file.parentFile, "${file.name}.tmp")

        val result = runCatching {
            file.parentFile?.mkdirs()
            if (tmp.exists()) tmp.delete()
            FileOutputStream(tmp).use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (!ok) throw IOException("Failed to compress bitmap")
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException("Failed to rename temp file")

            attachmentDao.deleteByNoteAndPath(noteId, file.absolutePath)
            attachmentDao.insert(
                Attachment(
                    noteId = noteId,
                    type = MapPreviewStorage.ATTACHMENT_TYPE,
                    path = file.absolutePath
                )
            )
        }

        if (result.isFailure) {
            Log.e(MapFragment.TAG, "Failed to persist map preview for block=$blockId", result.exceptionOrNull())
        }
    }
}
