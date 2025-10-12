package com.example.openeer.ui.library

import android.location.LocationManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.ui.library.MapFragment.Companion.MENU_ROUTE_GPS
import com.example.openeer.ui.library.MapFragment.Companion.MENU_ROUTE_MANUAL
import com.example.openeer.R
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.ui.map.MapText
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapRenderers
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.map.RoutePersistResult
import com.example.openeer.ui.map.RouteRecorder
import kotlin.collections.removeLastOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

internal fun MapFragment.onRouteButtonClicked() {
    when {
        routeRecorder != null -> stopRouteRecording(save = true)
        isManualRouteMode -> saveManualRoute()
        isSavingRoute -> Unit
        else -> showRouteModeMenu()
    }
}

internal fun MapFragment.showRouteModeMenu() {
    val context = requireContext()
    val popup = PopupMenu(context, binding.btnRecordRoute)
    popup.menu.add(0, MENU_ROUTE_GPS, 0, getString(R.string.map_route_menu_gps))
    popup.menu.add(0, MENU_ROUTE_MANUAL, 1, getString(R.string.map_route_menu_manual))
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            MENU_ROUTE_GPS -> {
                startGpsRouteRecordingFlow()
                true
            }
            MENU_ROUTE_MANUAL -> {
                startManualRouteDrawing()
                true
            }
            else -> false
        }
    }
    popup.show()
}

internal fun MapFragment.startGpsRouteRecordingFlow() {
    if (isManualRouteMode) {
        cancelManualRouteDrawing()
    }
    if (!hasLocationPermission()) {
        awaitingRoutePermission = true
        val previouslyRequested = hasRequestedLocationPermission
        val showFineRationale = shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val showCoarseRationale = shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        requestPermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_ROUTE
        )
        hasRequestedLocationPermission = true
        if (previouslyRequested && !showFineRationale && !showCoarseRationale) {
            showLocationDisabledHint()
        } else {
            showHint(getString(R.string.map_location_permission_needed))
        }
        return
    }
    startRouteRecording()
}

internal fun MapFragment.startManualRouteDrawing(seed: LatLng? = null, anchorLabel: String? = null) {
    if (isSavingRoute) return
    cancelRouteRecording()
    manualPoints.clear()
    manualAnchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
    isManualRouteMode = true
    binding.btnAddHere.isEnabled = false
    binding.btnCancelManualRoute.isVisible = true
    binding.manualRouteHint.isVisible = true
    seed?.let {
        if (manualPoints.size < MapUiDefaults.MAX_ROUTE_POINTS) {
            val point = LatLng(it.latitude, it.longitude)
            manualPoints.add(point)
            if (manualAnchorLabel == null) {
                manualAnchorLabel = MapText.formatLatLon(point.latitude, point.longitude)
            }
        }
    }
    manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)

    updateManualRouteUi()
    if (manualPoints.isNotEmpty()) {
        announceManualRoutePoints()
    }
}

internal fun MapFragment.handleManualMapTap(latLng: LatLng) {
    if (!isManualRouteMode) return
    if (manualPoints.size >= MapUiDefaults.MAX_ROUTE_POINTS) {
        showHint(getString(R.string.map_manual_route_limit, MapUiDefaults.MAX_ROUTE_POINTS))
        return
    }
    val point = LatLng(latLng.latitude, latLng.longitude)
    manualPoints.add(point)
    if (manualPoints.size == 1 && manualAnchorLabel == null) {
        manualAnchorLabel = MapText.formatLatLon(point.latitude, point.longitude)
    }
    manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)

    updateManualRouteUi()
    announceManualRoutePoints()
}

internal fun MapFragment.onManualRouteUndoClicked() {
    if (!isManualRouteMode || manualPoints.isEmpty() || isSavingRoute || isSnapshotInProgress) return
    manualPoints.removeLastOrNull()
    if (manualPoints.isEmpty()) {
        manualAnchorLabel = null
    }
    manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)

    updateManualRouteUi()
    announceManualRoutePoints()
}

internal fun MapFragment.announceManualRoutePoints() {
    if (!isAdded) return
    val count = manualPoints.size
    val message = resources.getQuantityString(R.plurals.map_manual_route_points_a11y, count, count)
    binding.root.announceForAccessibility(message)
}

internal fun MapFragment.setSnapshotInProgress(inProgress: Boolean) {
    if (isSnapshotInProgress == inProgress) return
    isSnapshotInProgress = inProgress
    refreshRouteButtonState()
}

internal fun MapFragment.refreshRouteButtonState() {
    if (!isAdded) return
    when {
        isManualRouteMode -> updateManualRouteUi()
        routeRecorder != null -> {
            binding.btnRecordRoute.isEnabled = !isSnapshotInProgress
            binding.btnRecordRoute.contentDescription = binding.btnRecordRoute.text
        }
        else -> {
            binding.btnRecordRoute.isEnabled = !isSavingRoute && !isSnapshotInProgress
            binding.btnRecordRoute.contentDescription = getString(R.string.map_route_start_cd)
        }
    }
}

internal fun MapFragment.showPreviewSavedToast() {
    val ctx = context ?: return
    android.widget.Toast.makeText(ctx, getString(R.string.map_preview_ready), android.widget.Toast.LENGTH_SHORT).show()
}

internal fun MapFragment.updateManualRouteUi() {
    if (!isManualRouteMode) return
    val count = manualPoints.size
    binding.btnRecordRoute.text = getString(R.string.map_manual_route_save, count)
    binding.btnRecordRoute.isEnabled = count >= 2 && !isSavingRoute && !isSnapshotInProgress
    binding.btnRecordRoute.contentDescription = getString(R.string.map_manual_route_save_cd)
    binding.btnCancelManualRoute.isVisible = true
    binding.btnCancelManualRoute.isEnabled = !isSavingRoute && !isSnapshotInProgress
    binding.btnUndoManualRoute.isVisible = count >= 1
    binding.btnUndoManualRoute.isEnabled = count >= 1 && !isSavingRoute && !isSnapshotInProgress
    binding.manualRouteHint.isVisible = true
}

internal fun MapFragment.saveManualRoute() {
    if (!isManualRouteMode || isSavingRoute) return
    if (manualPoints.size < 2) {
        showHint(getString(R.string.map_route_too_short))
        return
    }
    val pointsSnapshot = manualPoints.toList()
    val payload = buildManualRoutePayload(pointsSnapshot)
    val mirrorText = buildManualRouteMirrorText(pointsSnapshot)
    isSavingRoute = true
    binding.btnRecordRoute.isEnabled = false
    binding.btnCancelManualRoute.isEnabled = false
    viewLifecycleOwner.lifecycleScope.launch {
        val result = persistRoute(payload, mirrorText)
        isSavingRoute = false
        if (result == null) {
            showHint(getString(R.string.map_route_save_failed))
            updateManualRouteUi()
        } else {
            targetNoteId = result.noteId
            showHint(getString(R.string.map_route_saved))
            MapRenderers.fitToRoute(map, result.payload.points, requireContext())

            captureRoutePreview(result) {
                finishManualRouteDrawing()
            }
            refreshNotesAsync()
        }
    }
}

internal fun MapFragment.buildManualRoutePayload(points: List<LatLng>): RoutePayload {
    val now = System.currentTimeMillis()
    val stepMs = 1_000L
    val span = kotlin.math.max(points.size - 1, 0)
    val start = now - stepMs * span
    val routePoints = points.mapIndexed { index, latLng ->
        val timestamp = start + stepMs * index
        RoutePointPayload(latLng.latitude, latLng.longitude, timestamp)
    }
    val startedAt = routePoints.firstOrNull()?.t ?: now
    val endedAt = routePoints.lastOrNull()?.t ?: now
    return RoutePayload(
        startedAt = startedAt,
        endedAt = endedAt,
        points = routePoints
    )
}

internal fun MapFragment.buildManualRouteMirrorText(points: List<LatLng>): String {
    val base = getString(R.string.map_manual_route_mirror_format, points.size)
    val anchor = manualAnchorLabel?.takeIf { it.isNotBlank() }
        ?: points.firstOrNull()?.let { MapText.formatLatLon(it.latitude, it.longitude) }
    return if (anchor != null) {
        base + "\n" + getString(R.string.map_manual_route_anchor_format, anchor)
    } else {
        base
    }
}

internal fun MapFragment.cancelManualRouteDrawing() {
    if (!isManualRouteMode || isSavingRoute) return
    manualPoints.clear()
    manualAnchorLabel = null
    manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
    isManualRouteMode = false
    binding.btnCancelManualRoute.isVisible = false
    binding.btnCancelManualRoute.isEnabled = true
    binding.btnUndoManualRoute.isVisible = false
    binding.btnUndoManualRoute.isEnabled = true
    binding.manualRouteHint.isVisible = false
    binding.btnAddHere.isEnabled = true
    resetRouteButton()
}

internal fun MapFragment.finishManualRouteDrawing() {
    manualPoints.clear()
    manualAnchorLabel = null
    manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
    isManualRouteMode = false
    binding.btnCancelManualRoute.isVisible = false
    binding.btnCancelManualRoute.isEnabled = true
    binding.btnUndoManualRoute.isVisible = false
    binding.btnUndoManualRoute.isEnabled = true
    binding.manualRouteHint.isVisible = false
    binding.btnAddHere.isEnabled = true
    resetRouteButton()
}

internal fun MapFragment.startRouteRecording() {
    if (routeRecorder != null || isSavingRoute) return
    val ctx = requireContext()
    val manager = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
    if (manager == null) {
        showHint(getString(R.string.map_route_provider_unavailable))
        return
    }
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    ).filter { provider ->
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }.distinct()
    if (providers.isEmpty()) {
        showHint(getString(R.string.map_route_provider_unavailable))
        return
    }
    binding.btnAddHere.isEnabled = false
    binding.btnRecordRoute.text = getString(R.string.map_route_stop, 0)
    refreshRouteButtonState()
    showHint(getString(R.string.map_route_recording_hint))
    val recorder = RouteRecorder(manager, providers) { points -> onRoutePointsChanged(points) }
    routeRecorder = recorder
    val started = runCatching { recorder.start() }.isSuccess
    if (!started) {
        routeRecorder = null
        binding.btnAddHere.isEnabled = true
        resetRouteButton()
        showHint(getString(R.string.map_route_provider_unavailable))
    }
}

internal fun MapFragment.stopRouteRecording(save: Boolean) {
    val recorder = routeRecorder ?: return
    routeRecorder = null
    binding.btnAddHere.isEnabled = true
    if (!save) {
        recorder.cancel()
        recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)

        resetRouteButton()
        return
    }
    binding.btnRecordRoute.isEnabled = false
    val payload = recorder.stop()
    if (payload == null) {
        showHint(getString(R.string.map_route_too_short))
        recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)

        resetRouteButton()
        return
    }
    val mirrorText = getString(R.string.map_route_mirror_format, payload.pointCount)
    isSavingRoute = true
    viewLifecycleOwner.lifecycleScope.launch {
        val result = persistRoute(payload, mirrorText)
        isSavingRoute = false
        if (result == null) {
            showHint(getString(R.string.map_route_save_failed))
            recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)

        } else {
            targetNoteId = result.noteId
            showHint(getString(R.string.map_route_saved))
            MapRenderers.fitToRoute(map, result.payload.points, requireContext())

            captureRoutePreview(result)
            refreshNotesAsync()
        }
        resetRouteButton()
    }
}

internal fun MapFragment.cancelRouteRecording() {
    val recorder = routeRecorder ?: return
    routeRecorder = null
    recorder.cancel()
    binding.btnAddHere.isEnabled = true
    recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)
    resetRouteButton()
}

internal fun MapFragment.onRoutePointsChanged(points: List<RoutePointPayload>) {
    binding.btnRecordRoute.text = getString(R.string.map_route_stop, points.size)
    binding.btnRecordRoute.contentDescription = binding.btnRecordRoute.text
    recordingRouteLine = MapPolylines.updateRoutePolyline(polylineManager, recordingRouteLine, points)

}

internal fun MapFragment.resetRouteButton() {
    if (isManualRouteMode) {
        updateManualRouteUi()
        return
    }
    binding.btnRecordRoute.text = getString(R.string.map_route_start)
    refreshRouteButtonState()
}

internal suspend fun MapFragment.persistRoute(
    payload: RoutePayload,
    mirrorText: String
): RoutePersistResult? {
    val firstPoint = payload.firstPoint()
    val lat = firstPoint?.lat
    val lon = firstPoint?.lon
    return withContext(Dispatchers.IO) {
        runCatching {
            var noteId = targetNoteId
            if (noteId == null) {
                noteId = noteRepo.createTextNote(
                    body = "",
                    lat = lat,
                    lon = lon,
                    place = null,
                    accuracyM = null
                )
            } else if (lat != null && lon != null) {
                noteRepo.updateLocation(noteId!!, lat, lon, null, null)
            }
            val routeJson = routeGson.toJson(payload)
            val routeBlockId = blocksRepo.appendRoute(noteId!!, routeJson, lat, lon)
            val mirrorBlockId = blocksRepo.appendText(noteId!!, mirrorText)
            RoutePersistResult(
                noteId = noteId!!,
                routeBlockId = routeBlockId,
                mirrorBlockId = mirrorBlockId,
                payload = payload
            )
        }.onFailure { e ->
            android.util.Log.e(MapFragment.TAG, "Failed to persist route", e)
        }.getOrNull()
    }
}
