package com.example.openeer.ui.map

import org.maplibre.android.plugins.annotation.Symbol
import com.example.openeer.data.block.RoutePayload

/**
 * Petits modèles internes de la carte (sans logique).
 * On reprend exactement les signatures utilisées dans MapFragment.
 */
data class MapPin(
    val lat: Double,
    val lon: Double,
    val iconId: String,
    var symbol: Symbol? = null
)

data class LocationAddResult(
    val noteId: Long,
    val locationBlockId: Long,
    val mirrorBlockId: Long,
    val previousLat: Double?,
    val previousLon: Double?,
    val previousPlace: String?,
    val previousAccuracy: Float?
)

data class RoutePersistResult(
    val noteId: Long,
    val routeBlockId: Long,
    val mirrorBlockId: Long,
    val payload: RoutePayload
)

data class RecentHere(
    val lat: Double,
    val lon: Double,
    val timestamp: Long
)
