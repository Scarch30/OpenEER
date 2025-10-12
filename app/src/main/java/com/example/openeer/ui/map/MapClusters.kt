package com.example.openeer.ui.map

import com.example.openeer.data.Note
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Regroupement dynamique des notes en “cellules écran” (~48 px) selon le zoom courant.
 * Extraction 1:1 depuis MapFragment (même algo, mêmes propriétés).
 */
object MapClusters {

    fun renderGroupsForCurrentZoom(map: MapLibreMap?, allNotes: List<Note>) {
        val m = map ?: return
        val style = m.style ?: return
        if (allNotes.isEmpty()) return

        val cam = m.cameraPosition ?: return
        val target = cam.target ?: return  // ✅ null-safe
        val centerLat = target.latitude
        val zoom = cam.zoom

        // ~48 px à l’écran
        val clusterRadiusPx = 48.0
        val mpp = MapRenderers.metersPerPixelAtLat(centerLat, zoom) // mètres par pixel
        val radiusM = clusterRadiusPx * mpp

        val cosLat = cos(Math.toRadians(centerLat))
        val degLat = radiusM / 111_000.0
        val degLon = radiusM / (111_320.0 * max(0.2, cosLat))
        if (!degLat.isFinite() || !degLon.isFinite() || degLat <= 0.0 || degLon <= 0.0) return

        // Regroupe par “cellule” (key = indices de grille)
        val groups = HashMap<Pair<Int, Int>, MutableList<Note>>()
        allNotes.forEach { n ->
            val la = n.lat ?: return@forEach
            val lo = n.lon ?: return@forEach
            val gx = floor(la / degLat).toInt()
            val gy = floor(lo / degLon).toInt()
            groups.getOrPut(gx to gy) { mutableListOf() }.add(n)
        }

        // Synthèse en features
        val features = groups.map { (_, list) ->
            val lat = list.mapNotNull { it.lat }.average()
            val lon = list.mapNotNull { it.lon }.average()
            val count = list.size
            val title = list.firstOrNull()?.placeLabel ?: "Lieu"
            val icon = when {
                count > 4 -> MapStyleIds.ICON_MANY
                count > 1 -> MapStyleIds.ICON_FEW
                else -> MapStyleIds.ICON_SINGLE
            }
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("title", title)
                addNumberProperty("count", count)
                addNumberProperty("lat", lat)
                addNumberProperty("lon", lon)
                addStringProperty("icon", icon)
            }
        }

        style.getSourceAs<GeoJsonSource>(MapStyleIds.SRC_NOTES)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}
