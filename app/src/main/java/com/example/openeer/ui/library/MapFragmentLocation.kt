package com.example.openeer.ui.library

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
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
import kotlinx.coroutines.*
import java.util.Locale

// --- Scope app pour les tâches qui doivent survivre à la fermeture de la carte ---
private object AppScopes {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

// --- Utilitaires UI safe : n’interagissent avec la vue que si elle existe encore ---
private inline fun MapFragment.tryUi(block: () -> Unit) {
    runCatching {
        if (isAdded && view != null) block()
    }
}

internal fun MapFragment.hasLocationPermission(): Boolean {
    val ctx = requireContext()
    val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun MapFragment.showLocationDisabledHint() {
    showHint(
        getString(R.string.map_location_disabled),
        getString(R.string.map_location_open_settings)
    ) { openAppSettings() }
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
    if (isPickMode) {
        MapSnapDiag.trace { "HERE click ignored in pick mode" }
        return
    }
    val ctx = requireContext()
    val tick = MapSnapDiag.Ticker()
    MapSnapDiag.trace { "HERE click: start" }

    if (!hasLocationPermission()) {
        MapSnapDiag.trace { "HERE: no permission → request" }
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
        } else tryUi { showHint(getString(R.string.map_location_permission_needed)) }
        return
    }

    if (symbolManager == null) {
        MapSnapDiag.trace { "HERE: symbolManager is null → location unavailable" }
        tryUi { showHint(getString(R.string.map_location_unavailable)) }
        return
    }

    viewLifecycleOwner.lifecycleScope.launch {
        tryUi { binding.btnAddHere.isEnabled = false }
        try {
            MapSnapDiag.trace { "HERE: getFastPlace()…" }
            val place = getFastPlace()
            MapSnapDiag.trace { "HERE: got place=${place?.label ?: "null"} in ${tick.ms()} ms" }

            if (place == null) {
                tryUi { showHint(getString(R.string.map_location_unavailable)) }
                return@launch
            }

            val now = System.currentTimeMillis()
            val last = lastHereLocation
            if (last != null && now - last.timestamp < MapUiDefaults.HERE_COOLDOWN_MS) {
                val results = FloatArray(1)
                Location.distanceBetween(last.lat, last.lon, place.lat, place.lon, results)
                if (results[0] < MapUiDefaults.HERE_COOLDOWN_DISTANCE_M) {
                    MapSnapDiag.trace { "HERE: recent duplicate rejected (d=${results[0]}m)" }
                    tryUi { showHint(getString(R.string.map_location_recent_duplicate)) }
                    return@launch
                }
            }

            MapSnapDiag.trace { "HERE: appendLocation()…" }
            val result = appendLocation(place)
            MapSnapDiag.trace { "HERE: appendLocation() done in ${tick.ms()} ms (noteId=${result?.noteId}, block=${result?.locationBlockId})" }

            if (result == null) {
                tryUi { showHint(getString(R.string.map_location_unavailable)) }
                return@launch
            }

            targetNoteId = result.noteId
            onTargetNoteIdChanged(result.noteId)
            setTargetNoteLocation(place.lat, place.lon)
            val displayLabel = MapText.displayLabelFor(place)
            tryUi { addCustomPin(result.locationBlockId, place.lat, place.lon) }
            tryUi { showHint(getString(R.string.map_location_added)) }
            tryUi { refreshNotesAsync() }
            tryUi { showUndoSnackbar(result, displayLabel) }

            MapSnapDiag.trace { "HERE: captureLocationPreview()…" }
            runCatching {
                captureLocationPreview(result.noteId, result.locationBlockId, place.lat, place.lon)
            }
            MapSnapDiag.trace { "HERE: captureLocationPreview() returned in ${tick.ms()} ms" }

            lastHereLocation = RecentHere(place.lat, place.lon, now)

            // Si fallback label → enrichir async (note + bloc LOCATION)
            if (place.label == null || place.label == "Position actuelle" || place.label.startsWith("geo:")) {
                enrichNoteLabelAsync(
                    noteId = result.noteId,
                    locationBlockId = result.locationBlockId,
                    mirrorBlockId = result.mirrorBlockId, // sera ≤0, on ignore dedans
                    lat = place.lat,
                    lon = place.lon
                )
            }
        } finally {
            tryUi { binding.btnAddHere.isEnabled = true }
        }
    }
}

/** Place rapide : timeout 1.2 s puis fallback sur dernière position connue. */
internal suspend fun MapFragment.getFastPlace(timeoutMs: Long = 1200L): Place? {
    val ctx = requireContext()
    val fromOneShot = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) { runCatching { getOneShotPlace(ctx) }.getOrNull() }
    }
    if (fromOneShot != null) return fromOneShot

    val last = lastKnownQuick() ?: return null
    val label = "geo:${"%.6f".format(Locale.US, last.latitude)},${"%.6f".format(Locale.US, last.longitude)}"
    return Place(lat = last.latitude, lon = last.longitude, label = label, accuracyM = null)
}

/** Dernière position connue rapide. */
internal suspend fun MapFragment.lastKnownQuick(): Location? = withContext(Dispatchers.IO) {
    val lm = context?.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
    for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
        try {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(p)
            if (loc != null) return@withContext loc
        } catch (_: SecurityException) {}
    }
    null
}

/** Enrichit la note et le bloc LOCATION quand une adresse lisible est trouvée. */
private fun MapFragment.enrichNoteLabelAsync(
    noteId: Long,
    locationBlockId: Long,
    mirrorBlockId: Long, // peut être ≤ 0 : on ignore
    lat: Double,
    lon: Double
) {
    AppScopes.io.launch {
        MapSnapDiag.trace { "HERE: enrichNoteLabelAsync(start) note=$noteId @ $lat,$lon" }

        val label = reverseGeocodeWithTimeout(lat, lon, 3000L)
        if (label.isNullOrBlank()) {
            MapSnapDiag.trace { "HERE: enrichNoteLabelAsync(no-label)" }
            return@launch
        }

        // 1) Met à jour la note (placeLabel)
        runCatching {
            noteRepo.updateLocation(
                id = noteId,
                lat = lat,
                lon = lon,
                place = label,
                accuracyM = null
            )
        }

        // 2) Met à jour le bloc LOCATION (placeName)
        runCatching {
            blocksRepo.updateLocationLabel(locationBlockId, label)
        }

        // 3) (SUPPRIMÉ) Mise à jour de texte miroir — conservé en no-op si absent
        if (mirrorBlockId > 0) {
            runCatching {
                val mirrorText = MapText.buildMirrorText(
                    Place(lat = lat, lon = lon, label = label, accuracyM = null)
                )
                blocksRepo.updateText(mirrorBlockId, mirrorText)
            }
        }

        MapSnapDiag.trace { "HERE: enrichNoteLabelAsync(done) label=\"$label\"" }
        runCatching { withContext(Dispatchers.Main) { tryUi { refreshNotesAsync() } } }
    }
}

/** Reverse-geocoding moderne avec timeout. */
private suspend fun MapFragment.reverseGeocodeWithTimeout(
    lat: Double,
    lon: Double,
    timeoutMs: Long = 3000L
): String? = withContext(Dispatchers.IO) {
    try {
        val geocoder = android.location.Geocoder(requireContext(), Locale.getDefault())

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine<String?> { cont ->
                val start = System.nanoTime()
                val listener = android.location.Geocoder.GeocodeListener { results ->
                    val addr = results?.firstOrNull()
                    val label = addr?.getAddressLine(0) ?: addr?.locality ?: addr?.subLocality
                    val ms = (System.nanoTime() - start) / 1_000_000
                    MapSnapDiag.trace { "RG: callback ${label ?: "null"} in ${ms}ms" }
                    cont.resume(label, null)
                }
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeout = Runnable { if (cont.isActive) cont.resume(null, null) }
                handler.postDelayed(timeout, timeoutMs)
                geocoder.getFromLocation(lat, lon, 1, listener)
                cont.invokeOnCancellation { handler.removeCallbacks(timeout) }
            }
        } else {
            withTimeoutOrNull(timeoutMs) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.let {
                    it.getAddressLine(0) ?: it.locality ?: it.subLocality
                }
            }
        }
    } catch (_: Throwable) {
        null
    }
}

internal suspend fun MapFragment.appendLocation(place: Place): LocationAddResult? =
    withContext(Dispatchers.IO) {
        if (isPickMode) return@withContext null
        runCatching {
            var noteId = targetNoteId
            var prevLat: Double? = null
            var prevLon: Double? = null
            var prevPlace: String? = null
            var prevAcc: Float? = null

            if (noteId != null) {
                val existing = noteRepo.noteOnce(noteId)
                prevLat = existing?.lat
                prevLon = existing?.lon
                prevPlace = existing?.placeLabel
                prevAcc = existing?.accuracyM
            } else {
                // ⚠️ crée une note VIERGE avec meta de localisation, pas de “post-it”
                noteId = noteRepo.createTextNote("", place.lat, place.lon, place.label, place.accuracyM)
            }

            // Met à jour la note pour l’affichage en pied
            noteRepo.updateLocation(noteId!!, place.lat, place.lon, place.label, place.accuracyM)

            // ➜ Ajoute uniquement le bloc LOCATION (plus de bloc texte miroir)
            val locBlockId = blocksRepo.appendLocation(noteId, place.lat, place.lon, place.label)

            // mirrorBlockId absent → on encode -1L pour compat
            val mirrorBlockId = -1L

            LocationAddResult(noteId, locBlockId, mirrorBlockId, prevLat, prevLon, prevPlace, prevAcc)
        }.onFailure { e -> Log.e(MapFragment.TAG, "Failed to append location", e) }.getOrNull()
    }

internal fun MapFragment.showUndoSnackbar(result: LocationAddResult, displayLabel: String) {
    tryUi {
        Snackbar.make(binding.root, "${getString(R.string.map_location_added)} — $displayLabel", Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                viewLifecycleOwner.lifecycleScope.launch { undoLocationAdd(result) }
            }.show()
    }
}

internal suspend fun MapFragment.undoLocationAdd(result: LocationAddResult) {
    val success = withContext(Dispatchers.IO) {
        runCatching {
            blocksRepo.deleteBlock(result.locationBlockId)
            if (result.mirrorBlockId > 0) {
                // Compat si un ancien miroir existait
                blocksRepo.deleteBlock(result.mirrorBlockId)
            }
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
        tryUi { removeCustomPin(result.locationBlockId) }
        tryUi { refreshNotesAsync() }
        tryUi { showHint(getString(R.string.map_location_undo_success)) }
        runCatching {
            Snackbar.make(binding.root, getString(R.string.map_location_undo_success), Snackbar.LENGTH_SHORT).show()
        }
        lastHereLocation = null
        setTargetNoteLocation(result.previousLat, result.previousLon)
    } else {
        runCatching {
            Snackbar.make(binding.root, getString(R.string.map_location_unavailable), Snackbar.LENGTH_SHORT).show()
        }
    }
}
