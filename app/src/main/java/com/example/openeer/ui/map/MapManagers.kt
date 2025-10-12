package com.example.openeer.ui.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.style.layers.Property

/**
 * Helpers de création/paramétrage des managers d’annotations MapLibre.
 * Aucune logique métier, aucun accès au Fragment.
 */
object MapManagers {

    fun createSymbolManager(mapView: MapView, map: MapLibreMap, style: Style): SymbolManager {
        return SymbolManager(mapView, map, style).apply {
            iconAllowOverlap = true
            iconIgnorePlacement = true
        }
    }

    fun createLineManager(mapView: MapView, map: MapLibreMap, style: Style): LineManager {
        return LineManager(mapView, map, style).apply {
            lineCap = Property.LINE_CAP_ROUND
        }
    }
}
