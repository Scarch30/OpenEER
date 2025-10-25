package com.example.openeer.ui.library

import com.example.openeer.ui.map.MapClusters
import com.example.openeer.ui.map.MapIcons
import com.example.openeer.ui.map.MapManagers
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapStyleIds
import kotlin.math.max
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style

internal fun MapFragment.configureMap(initialMode: String) {
    val mapInstance = map ?: return
    isStyleReady = false
    MapSnapDiag.trace { "MF: onMapReady() — initialMode=$initialMode" }

    val styleUrl = "https://tiles.basemaps.cartocdn.com/gl/positron-gl-style/style.json"
    mapInstance.setStyle(styleUrl) { style ->
        MapSnapDiag.trace { "MF: style loaded ($styleUrl)" }
        prepareUi(style)
        if (initialMode == MapActivity.MODE_BROWSE) {
            centerOnUserIfPossible()
        }
        MapSnapDiag.trace { "MF: loadNotesThenRender()…" }
        loadNotesThenRender()
        val showPins = arguments?.getBoolean(ARG_SHOW_LIBRARY_PINS, false) == true
        map?.addOnCameraIdleListener {
            if (showPins) {
                MapSnapDiag.trace {
                    "MF: camera idle → renderGroupsForCurrentZoom (notes=${allNotes.size})"
                }
                MapClusters.renderGroupsForCurrentZoom(map, allNotes)
            }
            maybeDismissSelectionOnPan()
        }
        isStyleReady = true
        applyPendingBlockFocus()
        applyStartMode(initialMode)
        maybeStartUserLocationTracking()
    }

    mapInstance.addOnMapClickListener { latLng ->
        if (isManualRouteMode) {
            handleManualMapTap(latLng)
            return@addOnMapClickListener true
        }
        if (isPickMode) {
            handleMapSelectionTap(latLng)
            return@addOnMapClickListener true
        }
        val screenPt = map?.projection?.toScreenLocation(latLng)
        val features = screenPt?.let { screen ->
            map?.queryRenderedFeatures(screen, MapStyleIds.LYR_NOTES).orEmpty()
        }.orEmpty()
        val clusterFeature = features.firstOrNull()
        if (clusterFeature != null) {
            val lat = clusterFeature.getNumberProperty("lat")?.toDouble()
            val lon = clusterFeature.getNumberProperty("lon")?.toDouble()
            val title = clusterFeature.getStringProperty("title") ?: "Lieu"
            val count = clusterFeature.getNumberProperty("count")?.toInt() ?: 1
            if (lat != null && lon != null) {
                val currentZoom = map?.cameraPosition?.zoom ?: 5.0
                val targetZoom = max(currentZoom, 13.5)
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), targetZoom)
                )
                showHint("$title — $count note(s)")
                NotesBottomSheet.newInstance(lat, lon, title)
                    .show(parentFragmentManager, "notes_sheet")
                return@addOnMapClickListener true
            }
        }
        if (handleMapSelectionTap(latLng)) {
            return@addOnMapClickListener true
        }
        false
    }

    mapInstance.addOnMapLongClickListener { latLng ->
        when {
            isManualRouteMode -> {
                handleManualMapLongClick(latLng)
            }
            !isPickMode -> {
                handleMapSelectionTap(latLng)
            }
            else -> false
        }
    }
}

private fun MapFragment.prepareUi(style: Style) {
    map?.uiSettings?.isCompassEnabled = true
    map?.uiSettings?.isRotateGesturesEnabled = true
    map?.uiSettings?.isZoomGesturesEnabled = true
    map?.uiSettings?.isScrollGesturesEnabled = true

    MapIcons.ensureDefaultIcons(style, requireContext())
    MapIcons.ensureNotesSourceAndLayer(style)

    val mv = mapView ?: return
    val mapInstance = map ?: return

    symbolManager?.onDestroy()
    symbolManager = MapManagers.createSymbolManager(mv, mapInstance, style)
    locationPins.values.forEach { it.symbol = null }
    resetUserLocationSymbolForNewManager()
    refreshPins()
    renderUserLocationSymbol()

    polylineManager?.onDestroy()
    polylineManager = MapManagers.createLineManager(mv, mapInstance, style)
    recordingRouteLine = null
    manualRouteLine = null
    if (isManualRouteMode) {
        manualRouteLine = MapPolylines.updateManualRoutePolyline(
            polylineManager,
            manualRouteLine,
            manualPoints
        )
    }

    b.locationActions.isVisible = shouldShowLocationActions
    if (shouldShowLocationActions) {
        b.btnAddHere.isEnabled = true
        b.btnRecordRoute.isEnabled = true
    } else {
        b.btnAddHere.isEnabled = false
        b.btnRecordRoute.isEnabled = false
    }
    b.btnFavoriteHere.isVisible = !isPickMode
    b.btnFavoriteHere.isEnabled = true
}
