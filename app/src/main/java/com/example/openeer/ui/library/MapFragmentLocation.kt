package com.example.openeer.ui.library

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.Place
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.ui.map.LocationAddResult
import com.example.openeer.ui.map.MapText
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.map.RecentHere
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MapFragment.hasLocationPermission(): Boolean {
    val ctx = requireContext()
    val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == android.content.pm.PackageManager.PERMISSION_GRANTED || coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun MapFragment.showLocationDisabledHint() {
    showHint(
        getString(R.string.map_location_disabled),
        getString(R.string.map_location_open_settings)
    ) {
        openAppSettings()
    }
}

internal fun MapFragment.openAppSettings() {
    val context = requireContext()
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}

internal fun MapFragment.onAddHereClicked() {
    val ctx = requireContext()
    if (!hasLocationPermission()) {
        val previouslyRequested = hasRequestedLocationPermission
        val showFineRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        val showCoarseRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        awaitingHerePermission = true
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_LOC
        )
        hasRequestedLocationPermission = true
        if (previouslyRequested && !showFineRationale && !showCoarseRationale) {
            showLocationDisabledHint()
        } else {
            showHint(getString(R.string.map_location_permission_needed))
        }
        return
    }
    if (symbolManager == null) {
        showHint(getString(R.string.map_location_unavailable))
        return
    }

    viewLifecycleOwner.lifecycleScope.launch {
        binding.btnAddHere.isEnabled = false
        try {
            val place = withContext(Dispatchers.IO) {
                runCatching { getOneShotPlace(ctx) }.getOrNull()
            }
            if (place == null) {
                showHint(getString(R.string.map_location_unavailable))
                return@launch
            }
            val now = System.currentTimeMillis()
            val last = lastHereLocation
            if (last != null && now - last.timestamp < MapUiDefaults.HERE_COOLDOWN_MS) {
                val results = FloatArray(1)
                Location.distanceBetween(last.lat, last.lon, place.lat, place.lon, results)
                if (results[0] < MapUiDefaults.HERE_COOLDOWN_DISTANCE_M) {
                    showHint(getString(R.string.map_location_recent_duplicate))
                    return@launch
                }
            }
            val result = appendLocation(place)
            if (result == null) {
                showHint(getString(R.string.map_location_unavailable))
                return@launch
            }
            targetNoteId = result.noteId
            val displayLabel = MapText.displayLabelFor(place)
            addCustomPin(result.locationBlockId, place.lat, place.lon)
            showHint(getString(R.string.map_location_added))
            refreshNotesAsync()
            showUndoSnackbar(result, displayLabel)
            captureLocationPreview(result.noteId, result.locationBlockId, place.lat, place.lon)
            lastHereLocation = RecentHere(place.lat, place.lon, now)
        } finally {
            binding.btnAddHere.isEnabled = true
        }
    }
}

internal suspend fun MapFragment.appendLocation(place: Place): LocationAddResult? {
    return withContext(Dispatchers.IO) {
        runCatching {
            var noteId = targetNoteId
            var previousLat: Double? = null
            var previousLon: Double? = null
            var previousPlace: String? = null
            var previousAccuracy: Float? = null

            if (noteId != null) {
                val existing = noteRepo.noteOnce(noteId)
                previousLat = existing?.lat
                previousLon = existing?.lon
                previousPlace = existing?.placeLabel
                previousAccuracy = existing?.accuracyM
            } else {
                noteId = noteRepo.createTextNote(
                    body = "",
                    lat = place.lat,
                    lon = place.lon,
                    place = place.label,
                    accuracyM = place.accuracyM
                )
            }

            noteRepo.updateLocation(
                id = noteId!!,
                lat = place.lat,
                lon = place.lon,
                place = place.label,
                accuracyM = place.accuracyM
            )

            val locationBlockId = blocksRepo.appendLocation(noteId!!, place.lat, place.lon, place.label)
            val mirrorBlockId = blocksRepo.appendText(noteId!!, MapText.buildMirrorText(place))

            LocationAddResult(
                noteId = noteId!!,
                locationBlockId = locationBlockId,
                mirrorBlockId = mirrorBlockId,
                previousLat = previousLat,
                previousLon = previousLon,
                previousPlace = previousPlace,
                previousAccuracy = previousAccuracy
            )
        }.onFailure { e ->
            Log.e(MapFragment.TAG, "Failed to append location", e)
        }.getOrNull()
    }
}

internal fun MapFragment.showUndoSnackbar(result: LocationAddResult, displayLabel: String) {
    Snackbar.make(binding.root, "${getString(R.string.map_location_added)} — $displayLabel", Snackbar.LENGTH_LONG)
        .setAction(R.string.action_undo) {
            viewLifecycleOwner.lifecycleScope.launch {
                undoLocationAdd(result)
            }
        }
        .show()
}

internal suspend fun MapFragment.undoLocationAdd(result: LocationAddResult) {
    val success = withContext(Dispatchers.IO) {
        runCatching {
            blocksRepo.deleteBlock(result.locationBlockId)
            blocksRepo.deleteBlock(result.mirrorBlockId)
            noteRepo.updateLocation(
                id = result.noteId,
                lat = result.previousLat,
                lon = result.previousLon,
                place = result.previousPlace,
                accuracyM = result.previousAccuracy
            )
        }.isSuccess
    }
    if (success) {
        removeCustomPin(result.locationBlockId)
        refreshNotesAsync()
        showHint(getString(R.string.map_location_undo_success))
        Snackbar.make(binding.root, getString(R.string.map_location_undo_success), Snackbar.LENGTH_SHORT).show()
        lastHereLocation = null
    } else {
        Snackbar.make(binding.root, getString(R.string.map_location_unavailable), Snackbar.LENGTH_SHORT).show()
    }
}
