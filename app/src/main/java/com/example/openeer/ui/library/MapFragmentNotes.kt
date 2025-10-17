package com.example.openeer.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.ui.map.MapCamera
import com.example.openeer.ui.map.MapClusters
import com.example.openeer.ui.map.MapRenderers
import com.example.openeer.ui.map.MapUiDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.max

private val MapFragment.showLibraryPins: Boolean
    get() = arguments?.getBoolean(MapFragment.ARG_SHOW_LIBRARY_PINS, false) ?: false

internal fun MapFragment.loadNotesThenRender() {
    // En mode Carte/Map, on ne charge pas/affiche pas l’overlay des notes
    if (!showLibraryPins) return

    val dao = database.noteDao()
    viewLifecycleOwner.lifecycleScope.launch {
        allNotes = withContext(Dispatchers.IO) { dao.getAllWithLocation() }
        if (allNotes.isEmpty()) {
            showHint("Aucune note géolocalisée")
            return@launch
        } else {
            showHint("${allNotes.size} note(s) géolocalisée(s)")
        }
        MapClusters.renderGroupsForCurrentZoom(map, allNotes)
    }
}

internal fun MapFragment.applyStartMode(mode: String) {
    when (mode) {
        MapActivity.MODE_CENTER_ON_HERE -> {
            val centered = tryCenterOnLastKnownLocation()
            if (!centered) {
                MapCamera.moveCameraToDefault(map)
            }
        }
        MapActivity.MODE_FOCUS_NOTE -> focusOnTargetNoteOrFallback { MapCamera.moveCameraToDefault(map) }
        else -> Unit
    }
}

internal fun MapFragment.tryCenterOnLastKnownLocation(): Boolean {
    if (!hasLocationPermission()) return false
    val me = lastKnownLatLng(requireContext()) ?: return false
    val currentZoom = map?.cameraPosition?.zoom ?: 5.0
    val targetZoom = max(currentZoom, 14.0)
    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(me, targetZoom))
    showHint("Votre position")
    return true
}

internal fun MapFragment.focusOnTargetNoteOrFallback(onFallback: () -> Unit) {
    val noteId = targetNoteId ?: return onFallback()
    viewLifecycleOwner.lifecycleScope.launch {
        val note = withContext(Dispatchers.IO) { noteRepo.noteOnce(noteId) }
        val lat = note?.lat
        val lon = note?.lon
        if (lat != null && lon != null) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
        } else {
            onFallback()
        }
    }
}

internal fun MapFragment.applyPendingBlockFocus() {
    val blockId = pendingBlockFocus ?: return
    if (!isStyleReady || map == null) return
    viewLifecycleOwner.lifecycleScope.launch {
        val block = blocksRepo.getBlock(blockId)
        if (block != null) {
            targetBlockId = blockId
            targetNoteId = block.noteId
            onTargetNoteIdChanged(block.noteId)
            val blockLat = block.lat
            val blockLon = block.lon
            if (blockLat != null && blockLon != null) {
                setTargetNoteLocation(blockLat, blockLon)
            }
            focusOnBlock(block)
        }
        pendingBlockFocus = null
    }
}

internal fun MapFragment.focusOnBlock(block: BlockEntity) {
    when (block.type) {
        BlockType.ROUTE -> {
            val handled = focusOnRoute(block)
            if (!handled) {
                MapCamera.focusOnLatLon(map, block.lat, block.lon, onHint = { showHint(it) }, hint = getString(R.string.block_view_on_map))
            }
        }
        BlockType.LOCATION -> MapCamera.focusOnLatLon(map, block.lat, block.lon, onHint = { showHint(it) }, hint = getString(R.string.block_view_on_map))
        else -> MapCamera.focusOnLatLon(map, block.lat, block.lon, onHint = { showHint(it) }, hint = getString(R.string.block_view_on_map))
    }
}

internal fun MapFragment.focusOnRoute(block: BlockEntity): Boolean {
    val payload = block.routeJson?.let { json ->
        runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
    }
    val points = payload?.points ?: emptyList()
    if (points.isEmpty()) return false
    if (points.size == 1) {
        return MapCamera.focusOnLatLon(map, points.first().lat, points.first().lon, onHint = { showHint(it) }, hint = getString(R.string.block_view_on_map))
    }
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    val bounds = runCatching { builder.build() }.getOrNull()
    if (bounds != null) {
        val padding = MapRenderers.dpToPx(requireContext(), MapUiDefaults.ROUTE_BOUNDS_PADDING_DP)
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        showHint(getString(R.string.block_view_on_map))
        return true
    }
    return MapCamera.focusOnLatLon(map, points.first().lat, points.first().lon, onHint = { showHint(it) }, hint = getString(R.string.block_view_on_map))
}

internal fun MapFragment.refreshNotesAsync() {
    // En mode Carte/Map, on ne met pas à jour l’overlay des notes (no-op)
    if (!showLibraryPins) return

    viewLifecycleOwner.lifecycleScope.launch {
        val list = withContext(Dispatchers.IO) { database.noteDao().getAllWithLocation() }
        allNotes = list
        MapClusters.renderGroupsForCurrentZoom(map, allNotes)
    }
}

internal fun MapFragment.recenterToUserOrAll() {
    val ctx = requireContext()
    val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
        val previouslyRequested = hasRequestedLocationPermission
        val showFineRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        val showCoarseRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
        hasRequestedLocationPermission = true
        if (previouslyRequested && !showFineRationale && !showCoarseRationale) {
            showLocationDisabledHint()
        } else {
            showHint(getString(R.string.map_location_permission_needed))
        }
        return
    }
    val me = lastKnownLatLng(ctx)
    if (me != null) {
        val currentZoom = map?.cameraPosition?.zoom ?: 5.0
        val target = max(currentZoom, 14.0)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(me, target))
        showHint("Votre position")
    } else {
        lastFeatures?.let { MapRenderers.fitToAll(map, it, requireContext()) }
    }
}

internal fun MapFragment.lastKnownLatLng(ctx: Context): LatLng? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    for (p in providers) {
        @Suppress("MissingPermission")
        val loc = lm.getLastKnownLocation(p)
        if (loc != null) return LatLng(loc.latitude, loc.longitude)
    }
    return null
}
