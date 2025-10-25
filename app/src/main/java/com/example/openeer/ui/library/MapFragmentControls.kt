package com.example.openeer.ui.library

import android.util.Log
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResultListener
import com.example.openeer.R
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.map.RouteDebugOverlay
import com.example.openeer.ui.map.RouteDebugPreferences
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

internal fun MapFragment.initializeControlCenter() {
    b.btnZoomIn.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
    b.btnZoomOut.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
    b.btnRecenter.setOnClickListener { recenterToUserOrAll() }
    b.btnFavoriteHere.setOnClickListener { onFavoriteHereClicked() }
    b.btnFavoriteHere.isVisible = !isPickMode

    recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)

    val showLocationActions = shouldShowLocationActions
    b.locationActions.isVisible = showLocationActions
    if (showLocationActions) {
        b.btnAddHere.isEnabled = false
        b.btnAddHere.setOnClickListener { onAddHereClicked() }
        b.btnRecordRoute.isEnabled = false
        b.btnRecordRoute.setOnClickListener { onRouteButtonClicked() }
        b.manualRouteHint.isClickable = false
        b.manualRouteHint.isLongClickable = false
        b.btnUndoManualRoute.isVisible = false
        b.btnUndoManualRoute.setOnClickListener { onManualRouteUndoClicked() }
        b.btnCancelManualRoute.isVisible = false
        b.btnCancelManualRoute.setOnClickListener { onManualRouteCancelClicked() }
        refreshRouteButtonState()

        parentFragmentManager.setFragmentResultListener(RESULT_MANUAL_ROUTE, viewLifecycleOwner) { _, bundle ->
            val lat = bundle.getDouble(RESULT_MANUAL_ROUTE_LAT, Double.NaN)
            val lon = bundle.getDouble(RESULT_MANUAL_ROUTE_LON, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@setFragmentResultListener
            val label = bundle.getString(RESULT_MANUAL_ROUTE_LABEL)
            selectionLatLng = LatLng(lat, lon)
            manualAnchorLabel = label
            startManualRouteDrawing()
        }

        setupRouteUiBindings()
    } else {
        Log.d(TAG, "initializeControlCenter(): location actions hidden (pick=$isPickMode)")
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
