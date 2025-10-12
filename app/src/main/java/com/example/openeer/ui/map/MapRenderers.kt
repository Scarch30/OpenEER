package com.example.openeer.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.example.openeer.data.block.RoutePointPayload
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Helpers de rendu & caméra (aucune logique métier).
 * - dp<->px
 * - génération d'icônes (dot)
 * - fit bounds (features, latLngs, routes)
 * - métriques (mètres/pixel)
 */
object MapRenderers {

    fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).roundToInt()
    }

    fun makeDot(context: Context, sizeDp: Int = 18, colorRes: Int): Bitmap {
        val dm = context.resources.displayMetrics
        val px = (sizeDp * dm.density).toInt().coerceAtLeast(10)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, colorRes)
            style = Paint.Style.FILL
        }
        c.drawCircle(px / 2f, px / 2f, px / 2.2f, p)
        return bmp
    }

    /** m/pixel à une latitude pour un zoom donné. */
    fun metersPerPixelAtLat(lat: Double, zoom: Double): Double {
        return 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
    }

    /** Fit sur une liste de Features (Point). */
    fun fitToAll(map: MapLibreMap?, features: List<Feature>, context: Context, paddingDp: Int = MapUiDefaults.ROUTE_BOUNDS_PADDING_DP) {
        if (map == null || features.isEmpty()) return
        val b = LatLngBounds.Builder()
        features.forEach { f ->
            val p = f.geometry() as? Point ?: return@forEach
            b.include(LatLng(p.latitude(), p.longitude()))
        }
        val bounds = runCatching { b.build() }.getOrNull() ?: return
        val paddingPx = dpToPx(context, paddingDp)
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    /** Fit sur une liste de LatLng. */
    fun fitToLatLngs(map: MapLibreMap?, latLngs: List<LatLng>, context: Context, paddingDp: Int = MapUiDefaults.ROUTE_BOUNDS_PADDING_DP) {
        if (map == null || latLngs.isEmpty()) return
        if (latLngs.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 15.0))
            return
        }
        val b = LatLngBounds.Builder()
        latLngs.forEach { b.include(it) }
        val bounds = runCatching { b.build() }.getOrNull() ?: return
        val paddingPx = dpToPx(context, paddingDp)
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    /** Fit sur une route (RoutePointPayload). */
    fun fitToRoute(map: MapLibreMap?, points: List<RoutePointPayload>, context: Context, paddingDp: Int = MapUiDefaults.ROUTE_BOUNDS_PADDING_DP) {
        if (points.isEmpty()) return
        val latLngs = points.map { LatLng(it.lat, it.lon) }
        fitToLatLngs(map, latLngs, context, paddingDp)
    }
}
