package com.example.openeer.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

internal fun MapFragment.handlePermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    LocationPerms.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQ_ROUTE) {
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        awaitingRoutePermission = false
        if (granted) {
            startRouteRecording()
        } else {
            val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
            if (shouldShowRationale) {
                showHint(getString(R.string.map_location_permission_needed))
            } else {
                showLocationDisabledHint()
            }
        }
        return
    }
    if (requestCode == REQ_FAVORITE) {
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        awaitingFavoritePermission = false
        if (granted) {
            onFavoriteHerePermissionGranted()
        } else {
            context?.let {
                Toast.makeText(
                    it,
                    getString(R.string.map_favorite_here_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return
    }
    if (requestCode == REQ_LOC) {
        if (awaitingHerePermission) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            awaitingHerePermission = false
            if (granted) {
                maybeStartUserLocationTracking()
                onAddHereClicked()
            } else {
                val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                if (shouldShowRationale) {
                    showHint(getString(R.string.map_location_permission_needed))
                } else {
                    showLocationDisabledHint()
                }
                onLocationPermissionLost()
            }
            return
        }
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            maybeStartUserLocationTracking()
            recenterToUserOrAll()
        }
        if (grantResults.all { it == PackageManager.PERMISSION_DENIED }) {
            val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
            if (!shouldShowRationale) showLocationDisabledHint()
            onLocationPermissionLost()
        }
    }
}

internal fun MapFragment.recenterOnUserIfAvailable() {
    val ctx = context ?: return
    val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        ?: return
    val hasFine = ActivityCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ActivityCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) {
        Log.d(TAG, "centerOnUserIfPossible: missing location permission")
        return
    }
    val providers = listOf(
        android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.NETWORK_PROVIDER
    )
    val location = providers.firstNotNullOfOrNull { provider ->
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
    }
    if (location != null) {
        userLocationLatLng = LatLng(location.latitude, location.longitude)
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocationLatLng, 13.5))
        b.btnAddHere.isEnabled = true
        b.btnRecordRoute.isEnabled = true
        b.btnFavoriteHere.isVisible = !isPickMode
    } else {
        Log.d(TAG, "centerOnUserIfPossible: no last known location")
    }
}
