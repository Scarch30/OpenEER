package com.example.openeer.ui.map

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.ui.map.MapUiDefaults

/**
 * Extraction depuis MapFragment : enregistreur de route (throttling + limites).
 * API identique à l'inner class précédente.
 */
class RouteRecorder(
    private val locationManager: LocationManager,
    private val providers: List<String>,
    private val onPointsChanged: (List<RoutePointPayload>) -> Unit
) : LocationListener {

    private val points = mutableListOf<RoutePointPayload>()
    private var lastAcceptedAt: Long = 0L
    private var lastLocation: Location? = null

    @SuppressLint("MissingPermission")
    fun start() {
        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                MapUiDefaults.REQUEST_INTERVAL_MS,
                0f,
                this,
                Looper.getMainLooper()
            )
        }
        val seed = providers.firstNotNullOfOrNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        val now = System.currentTimeMillis()
        seed?.let { accept(it, now) }
    }

    override fun onLocationChanged(location: Location) {
        accept(location, System.currentTimeMillis())
    }

    private fun accept(location: Location, timestamp: Long) {
        if (points.isNotEmpty()) {
            // Throttle temporel
            if (timestamp - lastAcceptedAt < MapUiDefaults.MIN_TIME_BETWEEN_UPDATES_MS) {
                return
            }
            // Throttle spatial (distance minimale)
            val last = lastLocation
            if (last != null) {
                val d = location.distanceTo(last)
                if (d < MapUiDefaults.MIN_DISTANCE_METERS) {
                    return
                }
            }
            // Limite dure du nombre de points
            if (points.size >= MapUiDefaults.MAX_ROUTE_POINTS) {
                return
            }
        }
        val point = RoutePointPayload(location.latitude, location.longitude, timestamp)
        points.add(point)
        lastAcceptedAt = timestamp
        lastLocation = Location(location)
        onPointsChanged(points.toList())
    }

    fun stop(): RoutePayload? {
        providers.forEach { provider -> locationManager.removeUpdates(this) }
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        return RoutePayload(
            startedAt = first.t,
            endedAt = last.t,
            points = points.toList()
        )
    }

    fun cancel() {
        providers.forEach { provider -> locationManager.removeUpdates(this) }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
