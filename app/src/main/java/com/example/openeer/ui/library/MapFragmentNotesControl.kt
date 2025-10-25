package com.example.openeer.ui.library

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

internal fun MapFragment.handleTargetNoteIdChanged(newId: Long?) {
    if (targetNoteLocationNoteId != newId) {
        targetNoteLocation = null
        targetNoteLocationResolved = false
        targetNoteLocationLoading = false
        targetNoteLocationNoteId = newId
    }
    activity?.invalidateOptionsMenu()
    if (newId != null) {
        ensureTargetNoteLocation()
    }
}

internal fun MapFragment.updateTargetNoteLocation(lat: Double?, lon: Double?) {
    targetNoteLocation = if (lat != null && lon != null) LatLng(lat, lon) else null
    targetNoteLocationResolved = true
    targetNoteLocationLoading = false
    targetNoteLocationNoteId = targetNoteId
    activity?.invalidateOptionsMenu()
}

internal fun MapFragment.resolveTargetNoteLocation(force: Boolean) {
    val noteId = targetNoteId
    if (noteId == null) {
        if (targetNoteLocation != null || targetNoteLocationResolved) {
            targetNoteLocation = null
            targetNoteLocationResolved = false
            targetNoteLocationLoading = false
            targetNoteLocationNoteId = null
            activity?.invalidateOptionsMenu()
        }
        return
    }
    if (!force && targetNoteLocationResolved && targetNoteLocationNoteId == noteId) return
    if (targetNoteLocationLoading && targetNoteLocationNoteId == noteId) return
    targetNoteLocationLoading = true
    targetNoteLocationNoteId = noteId
    lifecycleScope.launch {
        val location = withContext(Dispatchers.IO) {
            val note = noteRepo.noteOnce(noteId)
            val lat = note?.lat
            val lon = note?.lon
            if (lat != null && lon != null) LatLng(lat, lon) else null
        }
        targetNoteLocation = location
        targetNoteLocationResolved = true
        targetNoteLocationLoading = false
        activity?.invalidateOptionsMenu()
    }
}
