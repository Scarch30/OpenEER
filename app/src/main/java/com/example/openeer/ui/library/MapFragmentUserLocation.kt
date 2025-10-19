package com.example.openeer.ui.library

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.openeer.ui.map.MapStyleIds
import com.example.openeer.ui.map.MapUiDefaults
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.plugins.annotation.SymbolOptions

private const val USER_LOCATION_TAG = "MapUserLocation"
private val USER_LOCATION_PROVIDERS = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.PASSIVE_PROVIDER
)

internal fun MapFragment.resetUserLocationSymbolForNewManager() {
    userLocationSymbol = null
}

internal fun MapFragment.renderUserLocationSymbol() {
    val manager = symbolManager ?: return
    val position = userLocationLatLng ?: return
    val existing = userLocationSymbol
    if (existing == null) {
        userLocationSymbol = manager.create(
            SymbolOptions()
                .withLatLng(position)
                .withIconImage(MapStyleIds.ICON_USER_LOCATION)
        )
    } else {
        existing.latLng = position
        manager.update(existing)
    }
}

internal fun MapFragment.onLocationPermissionLost() {
    stopUserLocationTracking(clearLocation = true)
}

@SuppressLint("MissingPermission")
internal fun MapFragment.maybeStartUserLocationTracking() {
    if (!isAdded) return
    if (!hasLocationPermission()) {
        onLocationPermissionLost()
        return
    }
    if (userLocationListener != null) {
        // Already tracking → ensure symbol is up to date with the current style.
        renderUserLocationSymbol()
        return
    }
    val ctx = context ?: return
    val manager = ContextCompat.getSystemService(ctx, LocationManager::class.java) ?: return
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleUserLocationUpdate(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    userLocationManager = manager
    userLocationListener = listener

    USER_LOCATION_PROVIDERS.forEach { provider ->
        runCatching {
            manager.requestLocationUpdates(
                provider,
                MapUiDefaults.REQUEST_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper()
            )
        }.onFailure { error ->
            Log.w(USER_LOCATION_TAG, "requestLocationUpdates failed for $provider", error)
        }
    }

    val seed = USER_LOCATION_PROVIDERS.firstNotNullOfOrNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }
    if (seed != null) {
        handleUserLocationUpdate(seed)
    }
}

internal fun MapFragment.stopUserLocationTracking(clearLocation: Boolean) {
    val manager = userLocationManager
    val listener = userLocationListener
    if (manager != null && listener != null) {
        runCatching { manager.removeUpdates(listener) }
    }
    userLocationListener = null
    userLocationManager = null
    if (clearLocation) {
        lastUserLocation = null
        userLocationLatLng = null
        userLocationSymbol?.let { symbol ->
            runCatching { symbolManager?.delete(symbol) }
        }
        userLocationSymbol = null
    }
}

internal fun MapFragment.handleUserLocationUpdate(location: Location) {
    val previous = lastUserLocation
    if (previous != null) {
        val distance = previous.distanceTo(location)
        if (distance < 0.5f) {
            return
        }
    }
    lastUserLocation = Location(location)
    userLocationLatLng = LatLng(location.latitude, location.longitude)
    renderUserLocationSymbol()
}
