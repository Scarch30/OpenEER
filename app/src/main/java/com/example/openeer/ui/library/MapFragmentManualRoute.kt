package com.example.openeer.ui.library

import android.util.Log
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapRenderers
import com.example.openeer.ui.sheets.MapSnapshotSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

internal fun MapFragment.beginManualRoute() {
    if (!shouldShowLocationActions) return
    if (isManualRouteMode) return
    Log.d(MapFragment.MANUAL_ROUTE_LOG_TAG, "startManualRouteDrawing(anchor=${manualAnchorLabel ?: ""})")
    isManualRouteMode = true
    isSavingRoute = false
    manualPoints.clear()
    manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
    refreshManualRouteUi()
}

internal fun MapFragment.endManualRoute() {
    if (!isManualRouteMode && manualPoints.isEmpty()) return
    Log.d(MapFragment.MANUAL_ROUTE_LOG_TAG, "finishManualRouteDrawing()")
    isManualRouteMode = false
    isSavingRoute = false
    manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
    manualPoints.clear()
    manualAnchorLabel = null
    refreshManualRouteUi()
    refreshRouteButtonState()
}

internal fun MapFragment.refreshManualRouteUi() {
    val binding = bindingOrNull ?: return
    if (!shouldShowLocationActions) {
        binding.locationActions.isVisible = false
        binding.manualRouteHint.isVisible = false
        return
    }
    val inManualMode = isManualRouteMode
    val pointCount = manualPoints.size
    val saving = isSavingRoute
    if (inManualMode) {
        val saveLabel = getString(R.string.map_manual_route_save, pointCount)
        binding.btnRecordRoute.text = saveLabel
        binding.btnRecordRoute.contentDescription = getString(R.string.map_manual_route_save_cd)
        binding.btnRecordRoute.isEnabled = pointCount >= 2 && !saving
        binding.btnCancelManualRoute.isVisible = true
        binding.btnCancelManualRoute.isEnabled = !saving
        binding.btnUndoManualRoute.isVisible = true
        binding.btnUndoManualRoute.isEnabled = pointCount > 0 && !saving
        binding.manualRouteHint.isVisible = true
        val anchor = manualAnchorLabel
        val hint = buildString {
            append(getString(R.string.map_manual_route_hint))
            if (!anchor.isNullOrBlank()) {
                append('\n')
                append(getString(R.string.map_manual_route_anchor_format, anchor))
            }
        }
        binding.manualRouteHint.text = hint
    } else {
        binding.manualRouteHint.isVisible = false
        binding.btnCancelManualRoute.isVisible = false
        binding.btnUndoManualRoute.isVisible = false
        binding.btnUndoManualRoute.isEnabled = false
        binding.btnCancelManualRoute.isEnabled = false
    }
}

internal fun MapFragment.processManualRouteTap(latLng: LatLng) {
    if (!isManualRouteMode || isSavingRoute) return
    if (manualPoints.size >= MapFragment.MANUAL_ROUTE_MAX_POINTS) {
        showHint(getString(R.string.map_manual_route_limit, MapFragment.MANUAL_ROUTE_MAX_POINTS))
        return
    }
    appendManualRoutePoint(latLng)
}

internal fun MapFragment.processManualRouteTap() {
    // No-op for reflective calls.
}

internal fun MapFragment.processManualRouteLongClick(latLng: LatLng): Boolean {
    if (!isManualRouteMode) return false
    if (isSavingRoute) return true
    if (manualPoints.size >= MapFragment.MANUAL_ROUTE_MAX_POINTS) {
        showHint(getString(R.string.map_manual_route_limit, MapFragment.MANUAL_ROUTE_MAX_POINTS))
        return true
    }
    appendManualRoutePoint(latLng)
    Log.d(
        MapFragment.MANUAL_ROUTE_LOG_TAG,
        "onMapLongClick(lat=${latLng.latitude}, lon=${latLng.longitude}) count=${manualPoints.size}"
    )
    return true
}

internal fun MapFragment.appendManualRoutePoint(latLng: LatLng) {
    manualPoints.add(latLng)
    manualRouteLine = MapPolylines.updateManualRoutePolyline(
        polylineManager,
        manualRouteLine,
        manualPoints
    )
    refreshManualRouteUi()
}

internal fun MapFragment.handleManualRouteUndo() {
    if (!isManualRouteMode || manualPoints.isEmpty() || isSavingRoute) return
    manualPoints.removeLast()
    manualRouteLine = MapPolylines.updateManualRoutePolyline(
        polylineManager,
        manualRouteLine,
        manualPoints
    )
    refreshManualRouteUi()
}

internal fun MapFragment.handleManualRouteCancel() {
    if (!isManualRouteMode) return
    Log.d(MapFragment.MANUAL_ROUTE_LOG_TAG, "cancelManualRoute()")
    endManualRoute()
}

internal fun MapFragment.persistManualRoute() {
    if (!isManualRouteMode || isSavingRoute) return
    val payload = createManualRoutePayload()
    if (payload == null) {
        showHint(getString(R.string.map_route_too_short))
        return
    }
    val mirrorText = createManualRouteMirrorText(payload)
    isSavingRoute = true
    refreshManualRouteUi()
    Log.d(MapFragment.MANUAL_ROUTE_LOG_TAG, "saveManualRoute(count=${payload.pointCount})")
    viewLifecycleOwner.lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            MapData.persistRoute(noteRepo, blocksRepo, routeGson, targetNoteId, payload, mirrorText)
        }
        if (result == null) {
            Log.w(MapFragment.MANUAL_ROUTE_LOG_TAG, "persistRoute returned null")
            showHint(getString(R.string.map_route_save_failed))
            isSavingRoute = false
            refreshManualRouteUi()
            return@launch
        }
        targetNoteId = result.noteId
        onTargetNoteIdChanged(result.noteId)
        val firstPoint = payload.points.firstOrNull()
        if (firstPoint != null) {
            setTargetNoteLocation(firstPoint.lat, firstPoint.lon)
        }
        refreshNotesAsync()
        context?.let { ctx ->
            MapRenderers.fitToRoute(map, payload.points, ctx)
        }
        captureRoutePreview(result) {
            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                MapSnapshotSheet.show(parentFragmentManager, result.routeBlockId)
            }
        }
        showHint(getString(R.string.map_route_saved))
        endManualRoute()
    }
}

internal fun MapFragment.createManualRoutePayload(): RoutePayload? {
    if (manualPoints.size < 2) return null
    val startedAt = System.currentTimeMillis()
    val points = manualPoints.mapIndexed { index, latLng ->
        RoutePointPayload(
            lat = latLng.latitude,
            lon = latLng.longitude,
            t = startedAt + index
        )
    }
    return RoutePayload(
        startedAt = startedAt,
        endedAt = startedAt + manualPoints.lastIndex,
        points = points
    )
}

internal fun MapFragment.createManualRouteMirrorText(payload: RoutePayload): String {
    val base = getString(R.string.map_manual_route_mirror_format, payload.pointCount)
    val anchor = manualAnchorLabel
    return if (!anchor.isNullOrBlank()) {
        base + "\n" + getString(R.string.map_manual_route_anchor_format, anchor)
    } else {
        base
    }
}
