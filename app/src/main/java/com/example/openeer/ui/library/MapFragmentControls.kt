package com.example.openeer.ui.library

import android.util.Log
import androidx.core.view.isVisible
import com.example.openeer.R
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapUiDefaults
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

internal fun MapFragment.initializeControlCenter() {
    binding.btnZoomIn.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
    binding.btnZoomOut.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
    binding.btnRecenter.setOnClickListener { recenterToUserOrAll() }
    binding.btnFavoriteHere.setOnClickListener { onFavoriteHereClicked() }
    binding.btnFavoriteHere.isVisible = !isPickMode

    recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)

    val showLocationActions = shouldShowLocationActions
    binding.locationActions.isVisible = showLocationActions
    if (showLocationActions) {
        binding.btnAddHere.isEnabled = false
        binding.btnAddHere.setOnClickListener { onAddHereClicked() }
        binding.btnRecordRoute.isEnabled = false
        binding.btnRecordRoute.setOnClickListener { onRouteButtonClicked() }
        binding.manualRouteHint.isClickable = false
        binding.manualRouteHint.isLongClickable = false
        binding.btnUndoManualRoute.isVisible = false
        binding.btnUndoManualRoute.setOnClickListener { onManualRouteUndoClicked() }
        binding.btnCancelManualRoute.isVisible = false
        binding.btnCancelManualRoute.setOnClickListener { onManualRouteCancelClicked() }
        refreshRouteButtonState()

        parentFragmentManager.setFragmentResultListener(
            MapFragment.RESULT_MANUAL_ROUTE,
            viewLifecycleOwner
        ) { _, bundle ->
            val lat = bundle.getDouble(MapFragment.RESULT_MANUAL_ROUTE_LAT, Double.NaN)
            val lon = bundle.getDouble(MapFragment.RESULT_MANUAL_ROUTE_LON, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@setFragmentResultListener
            val label = bundle.getString(MapFragment.RESULT_MANUAL_ROUTE_LABEL)
            selectionLatLng = LatLng(lat, lon)
            manualAnchorLabel = label
            startManualRouteDrawing()
        }

        setupRouteUiBindings()
    } else {
        Log.d(MapFragment.TAG, "initializeControlCenter(): location actions hidden (pick=$isPickMode)")
    }
}

internal fun MapFragment.manageDebugOverlay() {
    val ctx = context ?: return
    if (!RouteDebugPreferences.shouldExecuteOverlayCode(ctx)) {
        MapUiDefaults.DEBUG_ROUTE = false
        RouteDebugOverlay.hide(this)
    } else {
        RouteDebugPreferences.refreshDebugFlag(ctx)
        if (!MapUiDefaults.DEBUG_ROUTE) {
            RouteDebugOverlay.hide(this)
        }
    }
}
