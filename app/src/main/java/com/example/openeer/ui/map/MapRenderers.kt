package com.example.openeer.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.example.openeer.data.block.RoutePointPayload
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Helpers de rendu & caméra (aucune logique métier). */
object MapRenderers {

    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()

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
    fun metersPerPixelAtLat(lat: Double, zoom: Double): Double =
        156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)

    private const val METERS_PER_DEG_LAT = 111_320.0
    private fun metersPerDegLonAt(lat: Double): Double =
        METERS_PER_DEG_LAT * cos(Math.toRadians(lat))

    /** Fit sur une liste de Features (Point). */
    fun fitToAll(
        map: MapLibreMap?,
        features: List<Feature>,
        context: Context,
        paddingDp: Int = MapUiDefaults.ROUTE_SNAPSHOT_PADDING_DP,
        minSpanMeters: Float = MapUiDefaults.ROUTE_SNAPSHOT_MIN_SPAN_M,
        padFactor: Float = MapUiDefaults.ROUTE_SNAPSHOT_PAD_FACTOR
    ) {
        if (map == null || features.isEmpty()) return
        val latLngs = ArrayList<LatLng>(features.size)
        features.forEach { f ->
            val p = f.geometry() as? Point ?: return@forEach
            latLngs.add(LatLng(p.latitude(), p.longitude()))
        }
        fitToLatLngs(map, latLngs, context, paddingDp, minSpanMeters, padFactor)
    }

    /** Fit sur une liste de LatLng avec min-span & pad-factor. */
    fun fitToLatLngs(
        map: MapLibreMap?,
        latLngs: List<LatLng>,
        context: Context,
        paddingDp: Int = MapUiDefaults.ROUTE_SNAPSHOT_PADDING_DP,
        minSpanMeters: Float = MapUiDefaults.ROUTE_SNAPSHOT_MIN_SPAN_M,
        padFactor: Float = MapUiDefaults.ROUTE_SNAPSHOT_PAD_FACTOR
    ) {
        if (map == null || latLngs.isEmpty()) return

        if (latLngs.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 16.0))
            return
        }

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY

        for (ll in latLngs) {
            if (ll.latitude  < minLat) minLat = ll.latitude
            if (ll.latitude  > maxLat) maxLat = ll.latitude
            if (ll.longitude < minLon) minLon = ll.longitude
            if (ll.longitude > maxLon) maxLon = ll.longitude
        }

        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0
        val spanLatDeg = abs(maxLat - minLat)
        val spanLonDeg = abs(maxLon - minLon)

        val widthM  = spanLonDeg * metersPerDegLonAt(centerLat)
        val heightM = spanLatDeg * METERS_PER_DEG_LAT
        val spanM   = max(widthM, heightM)

        var targetSpanM = max(spanM.toFloat(), minSpanMeters)
        targetSpanM *= max(1f, padFactor)

        val halfM = targetSpanM / 2.0
        val deltaLatDeg = halfM / METERS_PER_DEG_LAT
        val deltaLonDeg = halfM / metersPerDegLonAt(centerLat)

        val expanded = LatLngBounds.Builder()
            .include(LatLng(centerLat - deltaLatDeg, centerLon - deltaLonDeg))
            .include(LatLng(centerLat + deltaLatDeg, centerLon + deltaLonDeg))
            .build()

        val paddingPx = dpToPx(context, paddingDp)
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(expanded, paddingPx))
    }

    /** Fit sur une route (RoutePointPayload). */
    fun fitToRoute(
        map: MapLibreMap?,
        points: List<RoutePointPayload>,
        context: Context,
        paddingDp: Int = MapUiDefaults.ROUTE_SNAPSHOT_PADDING_DP,
        minSpanMeters: Float = MapUiDefaults.ROUTE_SNAPSHOT_MIN_SPAN_M,
        padFactor: Float = MapUiDefaults.ROUTE_SNAPSHOT_PAD_FACTOR
    ) {
        if (points.isEmpty()) return
        val latLngs = points.map { LatLng(it.lat, it.lon) }
        fitToLatLngs(map, latLngs, context, paddingDp, minSpanMeters, padFactor)
    }
}
