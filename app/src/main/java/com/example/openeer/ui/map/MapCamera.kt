package com.example.openeer.ui.map

import android.content.Context
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature

/**
 * Helpers caméra : recentrer / focus sans logique métier de Fragment.
 */
object MapCamera {

    /**
     * Focus sur un lat/lon avec zoom (retourne true si effectué).
     * On peut passer une lambda pour afficher un hint, et un texte optionnel.
     */
    fun focusOnLatLon(
        map: MapLibreMap?,
        lat: Double?,
        lon: Double?,
        zoom: Double = 15.0,
        onHint: (String) -> Unit = {},
        hint: String? = null
    ): Boolean {
        if (lat == null || lon == null) return false
        val target = LatLng(lat, lon)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
        if (hint != null) onHint(hint)
        return true
    }

    /**
     * Recenter sur l’ensemble des features (fit bounds) si disponible.
     */
    fun recenterToAll(map: MapLibreMap?, features: List<Feature>?, context: Context) {
        features?.takeIf { it.isNotEmpty() }?.let {
            MapRenderers.fitToAll(map, it, context)
        }
    }

    /**
     * Déplacement caméra par défaut (France ~5.0).
     */
    fun moveCameraToDefault(map: MapLibreMap?) {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.7111, 1.7191), 5.0))
    }
}
