package com.example.openeer.map

import com.example.openeer.data.block.RoutePointPayload
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object RouteSimplifier {
    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val EPSILON_DEFAULT = 8.0
    private const val EPSILON_MEDIUM = 12.0
    private const val EPSILON_LARGE = 20.0
    private const val DISTANCE_THRESHOLD_MEDIUM = 3000.0
    private const val DISTANCE_THRESHOLD_LARGE = 10000.0

    fun simplifyMeters(points: List<RoutePointPayload>, epsilonM: Double): List<RoutePointPayload> {
        if (points.size < 3) return points

        val epsilon = max(0.0, epsilonM)
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true

        fun simplify(start: Int, end: Int) {
            if (end <= start + 1) return

            val startPoint = points[start]
            val endPoint = points[end]
            var maxDistance = 0.0
            var index = -1

            for (i in start + 1 until end) {
                val distance = distancePointToSegmentMeters(points[i], startPoint, endPoint)
                if (distance > maxDistance) {
                    maxDistance = distance
                    index = i
                }
            }

            if (index != -1 && maxDistance > epsilon) {
                keep[index] = true
                simplify(start, index)
                simplify(index, end)
            }
        }

        simplify(0, points.lastIndex)

        return points.indices
            .asSequence()
            .filter { keep[it] }
            .map { points[it] }
            .toList()
    }

    fun adaptiveEpsilonMeters(points: List<RoutePointPayload>): Double {
        val length = totalLengthMeters(points)
        return when {
            length > DISTANCE_THRESHOLD_LARGE -> EPSILON_LARGE
            length > DISTANCE_THRESHOLD_MEDIUM -> EPSILON_MEDIUM
            else -> EPSILON_DEFAULT
        }
    }

    fun totalLengthMeters(points: List<RoutePointPayload>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineDistanceMeters(points[i - 1], points[i])
        }
        return total
    }

    private fun haversineDistanceMeters(a: RoutePointPayload, b: RoutePointPayload): Double {
        val lat1 = Math.toRadians(a.lat)
        val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val aa = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        val c = 2 * atan2(sqrt(aa), sqrt(max(0.0, 1 - aa)))
        return EARTH_RADIUS_METERS * c
    }

    private fun distancePointToSegmentMeters(
        point: RoutePointPayload,
        start: RoutePointPayload,
        end: RoutePointPayload,
    ): Double {
        val startLat = Math.toRadians(start.lat)
        val startLon = Math.toRadians(start.lon)
        val endLat = Math.toRadians(end.lat)
        val endLon = Math.toRadians(end.lon)
        val pointLat = Math.toRadians(point.lat)
        val pointLon = Math.toRadians(point.lon)

        val segmentLength = haversineRadians(startLat, startLon, endLat, endLon)
        if (segmentLength < 1e-12) {
            return haversineDistanceMeters(point, start)
        }

        val delta13 = haversineRadians(startLat, startLon, pointLat, pointLon)
        if (delta13 < 1e-12) {
            return 0.0
        }

        val bearing12 = initialBearingRadians(startLat, startLon, endLat, endLon)
        val bearing13 = initialBearingRadians(startLat, startLon, pointLat, pointLon)
        val bearingDiff = normalizeRadians(bearing13 - bearing12)

        val sinCrossTrack = sin(delta13) * sin(bearingDiff)
        val crossTrackAngle = asin(sinCrossTrack.coerceIn(-1.0, 1.0))
        val crossTrackDistance = abs(crossTrackAngle) * EARTH_RADIUS_METERS

        val alongTrackAngle = atan2(
            sin(delta13) * cos(bearingDiff),
            cos(delta13)
        )

        return when {
            alongTrackAngle < 0 -> haversineDistanceMeters(point, start)
            alongTrackAngle > segmentLength -> haversineDistanceMeters(point, end)
            else -> crossTrackDistance
        }
    }

    private fun haversineRadians(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val aa = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * atan2(sqrt(aa), sqrt(max(0.0, 1 - aa)))
    }

    private fun initialBearingRadians(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return atan2(y, x)
    }

    private fun normalizeRadians(angle: Double): Double {
        var result = angle % (2 * PI)
        if (result > PI) result -= 2 * PI
        if (result < -PI) result += 2 * PI
        return result
    }
}
