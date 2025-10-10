package com.example.openeer.ui.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property

fun ensureSymbolManager(mapView: MapView, map: MapLibreMap): SymbolManager {
    val style = map.style ?: error("Map style is not ready")
    return SymbolManager(mapView, map, style).apply {
        iconAllowOverlap = true
        iconIgnorePlacement = true
    }
}

fun ensureLineManager(mapView: MapView, map: MapLibreMap): LineManager {
    val style = map.style ?: error("Map style is not ready")
    return LineManager(mapView, map, style).apply {
        lineCap = Property.LINE_CAP_ROUND
        lineJoin = Property.LINE_JOIN_ROUND
        lineColor = MapUiDefaults.ROUTE_LINE_COLOR
        lineWidth = MapUiDefaults.ROUTE_LINE_WIDTH
    }
}

fun renderRoute(lineManager: LineManager, points: List<LatLng>): Line {
    val options: LineOptions = LineOptions()
        .withLatLngs(points)
        .withLineColor(MapUiDefaults.ROUTE_LINE_COLOR)
        .withLineWidth(MapUiDefaults.ROUTE_LINE_WIDTH)
    @Suppress("UNCHECKED_CAST")
    return lineManager.create(options as LineOptions)
}

fun renderPin(symbolManager: SymbolManager, latLng: LatLng, iconId: String): Symbol {
    val options = SymbolOptions()
        .withLatLng(latLng)
        .withIconImage(iconId)
    return symbolManager.create(options)
}
