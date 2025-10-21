package com.example.openeer.ui.library

import android.Manifest
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.Place
import com.example.openeer.domain.favorites.FavoriteCreationRequest
import com.example.openeer.domain.favorites.FavoriteCreationRequest.Source
import com.example.openeer.domain.favorites.FavoriteCreationService
import com.example.openeer.domain.favorites.FavoriteNameSuggester
import com.example.openeer.ui.map.MapText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

internal fun MapFragment.onFavoriteHereClicked() {
    if (isPickMode) return
    if (!hasLocationPermission()) {
        awaitingFavoritePermission = true
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_FAVORITE
        )
        return
    }
    continueFavoriteHereFlow()
}

internal fun MapFragment.onFavoriteHerePermissionGranted() {
    maybeStartUserLocationTracking()
    continueFavoriteHereFlow()
}

private fun MapFragment.continueFavoriteHereFlow() {
    val ctx = context ?: return
    awaitingFavoritePermission = false
    viewLifecycleOwner.lifecycleScope.launch {
        val favoriteButton = runCatching { binding.btnFavoriteHere }.getOrNull()
        try {
            favoriteButton?.isEnabled = false
            val place = getFastPlace()
            if (place == null) {
                Toast.makeText(ctx, getString(R.string.map_favorite_here_location_unavailable), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val defaultName = FavoriteNameSuggester.defaultHereName(ctx)
            showFavoriteNameDialog(defaultName) { finalName ->
                FavoriteNameSuggester.recordNameUsage(ctx, finalName)
                launchFavoriteCreation(finalName, place.lat, place.lon, Source.HERE_BUTTON)
            }
        } finally {
            favoriteButton?.isEnabled = true
        }
    }
}

internal fun MapFragment.onAddFavoriteAtSelectionClicked() {
    val latLng = selectionLatLng ?: return
    val place = selectionPlace ?: Place(latLng.latitude, latLng.longitude, null, null)
    val ctx = context ?: return
    val label = MapText.displayLabelFor(place).takeIf { it.isNotBlank() }
    val defaultName = label ?: FavoriteNameSuggester.defaultSequentialName(ctx)
    showFavoriteNameDialog(defaultName) { finalName ->
        FavoriteNameSuggester.recordNameUsage(ctx, finalName)
        launchFavoriteCreation(finalName, latLng.latitude, latLng.longitude, Source.MAP_TAP)
        dismissSelectionSheet()
    }
}

private fun MapFragment.showFavoriteNameDialog(defaultName: String, onConfirm: (String) -> Unit) {
    val ctx = context ?: return
    val inputLayout = TextInputLayout(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hint = getString(R.string.map_favorite_name_hint)
    }
    val editText = TextInputEditText(inputLayout.context).apply {
        setText(defaultName)
        setSelection(defaultName.length)
        inputLayout.addView(this)
    }
    MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.map_favorite_dialog_title)
        .setView(inputLayout)
        .setPositiveButton(R.string.map_favorite_dialog_positive) { _, _ ->
            val entered = editText.text?.toString()?.trim().orEmpty()
            val finalName = if (entered.isNotEmpty()) entered else defaultName
            onConfirm(finalName)
            Toast.makeText(ctx, getString(R.string.map_favorite_creation_started), Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun MapFragment.launchFavoriteCreation(
    name: String,
    lat: Double,
    lon: Double,
    source: Source
) {
    val ctx = requireContext().applicationContext
    viewLifecycleOwner.lifecycleScope.launch {
        FavoriteCreationService.createFavorite(
            ctx,
            FavoriteCreationRequest(
                name = name,
                latitude = lat,
                longitude = lon,
                radiusMeters = 100,
                cooldownMinutes = 30,
                everyTime = false,
                source = source
            )
        )
    }
}
