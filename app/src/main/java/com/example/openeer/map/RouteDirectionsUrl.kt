package com.example.openeer.map

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng

private const val BASE_URL = "https://www.google.com/maps/dir/?api=1"
private const val MAX_WAYPOINTS = 9

fun buildMapsUrl(points: List<LatLng>, mode: String = "walking"): String? {
    if (points.size < 2) return null

    val origin = points.first()
    val destination = points.last()

    val intermediatePoints = if (points.size > 2) {
        points.subList(1, points.size - 1)
    } else {
        emptyList()
    }

    val waypoints = selectWaypoints(intermediatePoints)

    val params = linkedMapOf(
        "origin" to formatLatLng(origin),
        "destination" to formatLatLng(destination),
        "travelmode" to mode
    )

    if (waypoints.isNotEmpty()) {
        val encodedWaypoints = waypoints.joinToString(separator = "|") { formatLatLng(it) }
        params["waypoints"] = encodedWaypoints
    }

    val query = params.entries.joinToString(separator = "&") { (key, value) ->
        val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        "$key=$encodedValue"
    }

    return "$BASE_URL&$query"
}

private fun selectWaypoints(intermediatePoints: List<LatLng>): List<LatLng> {
    if (intermediatePoints.isEmpty()) return emptyList()

    val desiredCount = min(MAX_WAYPOINTS, intermediatePoints.size)
    if (desiredCount == intermediatePoints.size) {
        return intermediatePoints
    }

    if (desiredCount == 1) {
        return listOf(intermediatePoints.first())
    }

    val lastIndex = intermediatePoints.size - 1
    val step = lastIndex.toDouble() / (desiredCount - 1)
    val selected = ArrayList<LatLng>(desiredCount)
    var previousIndex = -1

    for (i in 0 until desiredCount) {
        val target = (i * step).roundToInt()
        val index = when {
            target <= previousIndex -> min(lastIndex, previousIndex + 1)
            target > lastIndex -> lastIndex
            else -> target
        }
        selected += intermediatePoints[index]
        previousIndex = index
    }

    return selected
}

private fun formatLatLng(point: LatLng): String {
    return String.format(Locale.US, "%.6f,%.6f", point.latitude, point.longitude)
}
