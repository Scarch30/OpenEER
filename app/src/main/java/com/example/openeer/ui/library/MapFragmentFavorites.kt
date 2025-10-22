package com.example.openeer.ui.library

import android.Manifest
import android.view.ViewGroup
import android.widget.LinearLayout
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
import kotlin.math.roundToInt

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
            val defaultAddress = MapText.displayLabelFor(place)
            showFavoriteNameDialog(defaultName, defaultAddress) { finalName, finalAddress ->
                FavoriteNameSuggester.recordNameUsage(ctx, finalName)
                launchFavoriteCreation(finalName, finalAddress, place.lat, place.lon, Source.HERE_BUTTON)
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
    val defaultAddress = label ?: MapText.displayLabelFor(place)
    showFavoriteNameDialog(defaultName, defaultAddress) { finalName, finalAddress ->
        FavoriteNameSuggester.recordNameUsage(ctx, finalName)
        launchFavoriteCreation(finalName, finalAddress, latLng.latitude, latLng.longitude, Source.MAP_TAP)
        dismissSelectionSheet()
    }
}

private fun MapFragment.showFavoriteNameDialog(
    defaultName: String,
    defaultAddress: String?,
    onConfirm: (name: String, address: String?) -> Unit
) {
    val ctx = context ?: return
    val density = ctx.resources.displayMetrics.density
    val padding = (16 * density).roundToInt()
    val spacing = (8 * density).roundToInt()
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, 0)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    val nameLayout = TextInputLayout(ctx).apply {
        hint = getString(R.string.map_favorite_name_hint)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    val shouldPrefillName = defaultName.isNotBlank() &&
        (defaultAddress == null || !defaultName.equals(defaultAddress, ignoreCase = true))
    val nameEditText = TextInputEditText(nameLayout.context).apply {
        if (shouldPrefillName) {
            setText(defaultName)
            setSelection(defaultName.length)
        }
        nameLayout.addView(this)
    }
    val addressLayout = TextInputLayout(ctx).apply {
        hint = getString(R.string.map_favorite_address_hint)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = spacing
        }
    }
    val addressEditText = TextInputEditText(addressLayout.context).apply {
        val address = defaultAddress.orEmpty()
        if (address.isNotEmpty()) {
            setText(address)
            setSelection(address.length)
        }
        addressLayout.addView(this)
    }
    container.addView(nameLayout)
    container.addView(addressLayout)
    MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.map_favorite_dialog_title)
        .setView(container)
        .setPositiveButton(R.string.map_favorite_dialog_positive) { _, _ ->
            val enteredName = nameEditText.text?.toString()?.trim().orEmpty()
            val sequentialFallback = FavoriteNameSuggester.defaultSequentialName(ctx)
            val fallbackName = when {
                defaultName.isNotBlank() &&
                    (defaultAddress == null || !defaultName.equals(defaultAddress, ignoreCase = true)) -> defaultName
                else -> sequentialFallback
            }
            val finalName = when {
                enteredName.isNotEmpty() -> enteredName
                fallbackName.isNotBlank() -> fallbackName
                !defaultAddress.isNullOrBlank() -> defaultAddress
                else -> sequentialFallback
            }
            val enteredAddress = addressEditText.text?.toString()?.trim().orEmpty()
            val finalAddress = when {
                enteredAddress.isNotEmpty() -> enteredAddress
                !defaultAddress.isNullOrBlank() -> defaultAddress
                else -> null
            }
            onConfirm(finalName, finalAddress)
            Toast.makeText(ctx, getString(R.string.map_favorite_creation_started), Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun MapFragment.launchFavoriteCreation(
    name: String,
    address: String?,
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
                address = address,
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
