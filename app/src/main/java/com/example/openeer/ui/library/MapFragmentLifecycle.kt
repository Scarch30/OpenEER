package com.example.openeer.ui.library

import com.example.openeer.core.LocationPerms
import com.example.openeer.ui.map.MapPolylines

internal fun MapFragment.handleOnResume() {
    if (waitingBgSettingsReturn) {
        waitingBgSettingsReturn = false
        val ctx = requireContext().applicationContext
        val nowHasBg = !LocationPerms.requiresBackground(ctx) || LocationPerms.hasBackground(ctx)
        MapSnapDiag.trace { "GeoFlow onResume ← from Settings, hasBG=$nowHasBg" }
        if (nowHasBg) {
            pendingGeo?.invoke()
            pendingGeo = null
        } else {
            MapSnapDiag.trace { "GeoFlow onResume: BG still missing, not scheduling geofence" }
            pendingGeo = null
        }
    }
    mapView?.onResume()
    maybeStartUserLocationTracking()
}

internal fun MapFragment.handleOnPause() {
    stopUserLocationTracking(clearLocation = false)
    mapView?.onPause()
}

internal fun MapFragment.cleanupOnDestroyView() {
    selectionDialog?.setOnDismissListener(null)
    selectionDialog?.dismiss()
    handleSelectionDismissed()
    mapView?.onDestroy()
    symbolManager?.onDestroy()
    symbolManager = null
    manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
    polylineManager?.onDestroy()
    polylineManager = null
    recordingRouteLine = null
    manualRouteLine = null
    manualPoints.clear()
    manualAnchorLabel = null
    isManualRouteMode = false
    isStyleReady = false
    pendingBlockFocus = targetBlockId
    locationPins.values.forEach { it.symbol = null }
    stopUserLocationTracking(clearLocation = true)
    searchJob?.cancel()
    searchJob = null
    searchAdapter = null
    searchResults = emptyList()
    searchExecutionJob?.cancel()
    searchExecutionJob = null
    bindingOrNull?.clusterHint?.let { hintView ->
        hintDismissRunnable?.let { hintView.removeCallbacks(it) }
    }
    hintDismissRunnable = null
    backgroundPermissionDialog?.dismiss()
    backgroundPermissionDialog = null
}
