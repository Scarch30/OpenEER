package com.example.openeer.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.Place
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.databinding.SheetMapSelectedLocationBinding
import com.example.openeer.ui.map.MapPin
import com.example.openeer.ui.map.MapStyleIds
import com.example.openeer.ui.map.MapText
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.plugins.annotation.SymbolOptions
import kotlin.math.abs

internal fun MapFragment.refreshPins() {
    val manager = symbolManager ?: return
    locationPins.forEach { (_, pin) ->
        val latLng = LatLng(pin.lat, pin.lon)
        val existing = pin.symbol
        if (existing == null) {
            val symbol = manager.create(
                SymbolOptions()
                    .withLatLng(latLng)
                    .withIconImage(pin.iconId)
            )
            pin.symbol = symbol
        } else {
            existing.latLng = latLng
            manager.update(existing)
        }
    }
}

internal fun MapFragment.addCustomPin(blockId: Long, lat: Double, lon: Double) {
    locationPins[blockId]?.symbol?.let { existing ->
        runCatching { symbolManager?.delete(existing) }
    }
    locationPins[blockId] = MapPin(lat, lon, MapStyleIds.ICON_HERE)
    refreshPins()
}

internal fun MapFragment.removeCustomPin(blockId: Long) {
    val pin = locationPins.remove(blockId)
    if (pin != null) {
        pin.symbol?.let { existing ->
            runCatching { symbolManager?.delete(existing) }
        }
    }
}

internal fun MapFragment.handleMapSelectionTap(latLng: LatLng): Boolean {
    if (isManualRouteMode) return false
    val manager = symbolManager ?: return false
    selectionJob?.cancel()
    selectionJob = null
    dismissSelectionSheet()

    selectionLatLng = latLng
    selectionPlace = Place(latLng.latitude, latLng.longitude, null, null)

    selectionSymbol?.let { existing ->
        runCatching { manager.delete(existing) }
    }
    selectionSymbol = manager.create(
        SymbolOptions()
            .withLatLng(latLng)
            .withIconImage(MapStyleIds.ICON_SELECTION)
    )

    val placeholderLabel = MapText.formatLatLon(latLng.latitude, latLng.longitude)
    showSelectionSheet(placeholderLabel, latLng, showLoading = true)

    val appCtx = requireContext().applicationContext
    selectionJob = viewLifecycleOwner.lifecycleScope.launch {
        val resolved = withContext(Dispatchers.IO) {
            runCatching { getOneShotPlace(appCtx, latLng.latitude, latLng.longitude) }.getOrNull()
        }
        if (!isActive) return@launch
        val current = selectionLatLng
        if (current == null || current.latitude != latLng.latitude || current.longitude != latLng.longitude) {
            return@launch
        }
        val place = resolved ?: Place(latLng.latitude, latLng.longitude, null, null)
        selectionPlace = place
        updateSelectionSheet(place, showLoading = false)
    }
    return true
}

internal fun MapFragment.showSelectionFromSearch(place: Place) {
    val latLng = LatLng(place.lat, place.lon)

    map?.let { mapInstance ->
        val currentZoom = mapInstance.cameraPosition?.zoom ?: 0.0
        val cameraUpdate = if (currentZoom < 13.5) {
            CameraUpdateFactory.newLatLngZoom(latLng, 15.0)
        } else {
            CameraUpdateFactory.newLatLng(latLng)
        }
        mapInstance.animateCamera(cameraUpdate)
    }

    if (!isPickMode) {
        if (selectionDialog != null) {
            dismissSelectionSheet()
        } else {
            handleSelectionDismissed()
        }
        return
    }

    val manager = symbolManager ?: return
    selectionJob?.cancel()
    selectionJob = null

    selectionLatLng = latLng
    selectionPlace = place

    selectionSymbol?.let { existing ->
        runCatching { manager.delete(existing) }
    }
    selectionSymbol = manager.create(
        SymbolOptions()
            .withLatLng(latLng)
            .withIconImage(MapStyleIds.ICON_SELECTION)
    )

    val label = MapText.displayLabelFor(place)
    showSelectionSheet(label, latLng, showLoading = false)
    updateSelectionSheet(place, showLoading = false)
}

internal fun MapFragment.showSelectionSheet(initialLabel: String, latLng: LatLng, showLoading: Boolean) {
    val ctx = context ?: return
    val inflater = LayoutInflater.from(ctx)
    val binding = SheetMapSelectedLocationBinding.inflate(inflater)
    val coordinates = String.format(
        java.util.Locale.US,
        getString(R.string.map_pick_coordinates_format),
        latLng.latitude,
        latLng.longitude
    )
    binding.label.text = initialLabel
    binding.coordinates.text = coordinates
    binding.loadingGroup.isVisible = showLoading
    binding.btnSave.isEnabled = true
    binding.btnRoute.isEnabled = !isPickMode
    binding.btnOpenMaps.isEnabled = true
    binding.btnRoute.isVisible = !isPickMode
    if (isPickMode) {
        binding.btnSave.text = getString(R.string.reminder_plan)
    }
    binding.btnSave.setOnClickListener { onSaveSelectedLocationClicked() }
    binding.btnRoute.setOnClickListener { onStartRouteFromSelectionClicked() }
    binding.btnOpenMaps.setOnClickListener { onOpenInGoogleMapsClicked() }

    selectionBinding = binding
    selectionDialog?.setOnDismissListener(null)
    selectionDialog?.dismiss()
    val dialog = BottomSheetDialog(ctx)
    dialog.setContentView(binding.root)
    dialog.setOnDismissListener { handleSelectionDismissed() }
    dialog.show()
    selectionDialog = dialog
}

internal fun MapFragment.maybeDismissSelectionOnPan() {
    val latLng = selectionLatLng ?: return
    val mapObj: MapLibreMap = map ?: return
    val mapViewObj: MapView = mapView ?: return
    if (mapViewObj.width == 0 || mapViewObj.height == 0) return
    val screenPoint = runCatching { mapObj.projection.toScreenLocation(latLng) }.getOrNull() ?: return
    val dx = abs(screenPoint.x - mapViewObj.width / 2f)
    val dy = abs(screenPoint.y - mapViewObj.height / 2f)
    val thresholdX = mapViewObj.width * 2f
    val thresholdY = mapViewObj.height * 2f
    if (dx > thresholdX || dy > thresholdY) {
        if (selectionDialog != null) {
            dismissSelectionSheet()
        } else {
            handleSelectionDismissed()
        }
    }
}

internal fun MapFragment.updateSelectionSheet(place: Place, showLoading: Boolean) {
    val binding = selectionBinding ?: return
    binding.label.text = MapText.displayLabelFor(place)
    binding.loadingGroup.isVisible = showLoading
}

internal fun MapFragment.onSaveSelectedLocationClicked() {
    val latLng = selectionLatLng ?: return
    val place = selectionPlace ?: Place(latLng.latitude, latLng.longitude, null, null)
    val binding = selectionBinding ?: return
    binding.btnSave.isEnabled = false
    binding.btnRoute.isEnabled = false
    binding.btnOpenMaps.isEnabled = false

    if (isPickMode) {
        deliverPickedLocation(place)
        return
    }

    viewLifecycleOwner.lifecycleScope.launch {
        val result = appendLocation(place)
        if (result == null) {
            binding.btnSave.isEnabled = true
            binding.btnRoute.isEnabled = true
            binding.btnOpenMaps.isEnabled = true
            showHint(getString(R.string.map_location_unavailable))
            return@launch
        }
        targetNoteId = result.noteId
        onTargetNoteIdChanged(result.noteId)
        setTargetNoteLocation(place.lat, place.lon)
        addCustomPin(result.locationBlockId, place.lat, place.lon)
        showHint(getString(R.string.map_location_added))
        refreshNotesAsync()
        showUndoSnackbar(result, MapText.displayLabelFor(place))
        captureLocationPreview(result.noteId, result.locationBlockId, place.lat, place.lon)
        dismissSelectionSheet()
    }
}

internal fun MapFragment.deliverPickedLocation(place: Place) {
    val latLng = selectionLatLng ?: return
    val label = MapText.displayLabelFor(place)
    val bundle = bundleOf(
        MapFragment.RESULT_PICK_LOCATION_LAT to latLng.latitude,
        MapFragment.RESULT_PICK_LOCATION_LON to latLng.longitude,
        MapFragment.RESULT_PICK_LOCATION_LABEL to label
    )
    parentFragmentManager.setFragmentResult(MapFragment.RESULT_PICK_LOCATION, bundle)
    val intent = Intent().apply {
        putExtra(MapFragment.RESULT_PICK_LOCATION_LAT, latLng.latitude)
        putExtra(MapFragment.RESULT_PICK_LOCATION_LON, latLng.longitude)
        putExtra(MapFragment.RESULT_PICK_LOCATION_LABEL, label)
    }
    activity?.setResult(Activity.RESULT_OK, intent)
    dismissSelectionSheet()
    activity?.finish()
}

internal fun MapFragment.onStartRouteFromSelectionClicked() {
    val latLng = selectionLatLng ?: return
    val place = selectionPlace ?: Place(latLng.latitude, latLng.longitude, null, null)
    val label = MapText.displayLabelFor(place)
    parentFragmentManager.setFragmentResult(
        MapFragment.RESULT_MANUAL_ROUTE,
        bundleOf(
            MapFragment.RESULT_MANUAL_ROUTE_LAT to latLng.latitude,
            MapFragment.RESULT_MANUAL_ROUTE_LON to latLng.longitude,
            MapFragment.RESULT_MANUAL_ROUTE_LABEL to label
        )
    )
    showHint(getString(R.string.map_manual_route_seed_hint, label))
    dismissSelectionSheet()
}

internal fun MapFragment.onOpenInGoogleMapsClicked() {
    val latLng = selectionLatLng ?: return
    val place = selectionPlace ?: Place(latLng.latitude, latLng.longitude, null, null)
    val label = MapText.displayLabelFor(place)
    val lat = latLng.latitude
    val lon = latLng.longitude
    val encodedLabel = Uri.encode(label)
    val geoUri = Uri.parse("geo:0,0?q=$lat,$lon($encodedLabel)")
    val ctx = requireContext()
    val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
    val pm = ctx.packageManager
    var launched = false
    if (geoIntent.resolveActivity(pm) != null) {
        launched = runCatching { startActivity(geoIntent) }.isSuccess
    }
    if (!launched) {
        val url = String.format(java.util.Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon)
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (fallbackIntent.resolveActivity(pm) != null) {
            launched = runCatching { startActivity(fallbackIntent) }.isSuccess
        }
    }
    if (!launched) {
        Toast.makeText(ctx, getString(R.string.map_pick_google_maps_unavailable), Toast.LENGTH_SHORT).show()
    }
    dismissSelectionSheet()
}

internal fun MapFragment.dismissSelectionSheet() {
    selectionDialog?.dismiss()
}

internal fun MapFragment.handleSelectionDismissed() {
    selectionJob?.cancel()
    selectionJob = null
    selectionBinding = null
    selectionDialog = null
    selectionPlace = null
    selectionLatLng = null
    selectionSymbol?.let { symbol ->
        runCatching { symbolManager?.delete(symbol) }
    }
    selectionSymbol = null
}
