package com.example.openeer.ui.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import com.example.openeer.data.block.RoutePointPayload

/**
 * Helpers de gestion des polylignes (création/màj/suppression).
 * Zéro logique métier et aucune dépendance au Fragment.
 */
object MapPolylines {

    /** Met à jour la polyline d'enregistrement GPS ; retourne la nouvelle Line (ou null). */
    fun updateRoutePolyline(
        manager: LineManager?,
        currentLine: Line?,
        points: List<RoutePointPayload>
    ): Line? {
        val m = manager ?: return null
        currentLine?.let { runCatching { m.delete(it) } }
        if (points.size < 2) return null
        val latLngs = points.map { LatLng(it.lat, it.lon) }
        val options: LineOptions = LineOptions()
            .withLatLngs(latLngs)
            .withLineColor(MapUiDefaults.ROUTE_LINE_COLOR)
            .withLineWidth(MapUiDefaults.ROUTE_LINE_WIDTH)
        return m.create(options as LineOptions)
    }

    /** Supprime la polyline d'enregistrement ; retourne toujours null. */
    fun clearRecordingLine(manager: LineManager?, currentLine: Line?): Line? {
        currentLine?.let { line -> runCatching { manager?.delete(line) } }
        return null
    }

    /** Met à jour la polyline de tracé manuel ; retourne la nouvelle Line (ou null). */
    fun updateManualRoutePolyline(
        manager: LineManager?,
        currentLine: Line?,
        manualPoints: List<LatLng>
    ): Line? {
        val m = manager ?: return null
        currentLine?.let { runCatching { m.delete(it) } }
        if (manualPoints.size < 2) return null
        val options: LineOptions = LineOptions()
            .withLatLngs(manualPoints.toList())
            .withLineColor(MapUiDefaults.ROUTE_LINE_COLOR)
            .withLineWidth(MapUiDefaults.ROUTE_LINE_WIDTH)
        return m.create(options as LineOptions)
    }

    /** Supprime la polyline de tracé manuel ; retourne toujours null. */
    fun clearManualRouteLine(manager: LineManager?, currentLine: Line?): Line? {
        currentLine?.let { line -> runCatching { manager?.delete(line) } }
        return null
    }
}
