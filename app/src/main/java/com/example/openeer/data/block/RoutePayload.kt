package com.example.openeer.data.block

/**
 * Modèle léger pour persister un itinéraire manuel.
 * - version : permet d'évoluer le schéma JSON sans casser les lectures.
 * - startedAt / endedAt : timestamps Unix en millisecondes.
 * - points : séquence bornée de points ordonnés dans le temps.
 */
data class RoutePayload(
    val version: Int = 1,
    val startedAt: Long,
    val endedAt: Long,
    val points: List<RoutePointPayload>
) {
    val pointCount: Int get() = points.size
    val durationMs: Long get() = endedAt - startedAt
    val hasEnoughPoints: Boolean get() = points.size >= MIN_POINTS

    fun firstPoint(): RoutePointPayload? = points.firstOrNull()
    fun lastPoint(): RoutePointPayload? = points.lastOrNull()

    companion object {
        private const val MIN_POINTS = 2
    }
}

data class RoutePointPayload(
    val lat: Double,
    val lon: Double,
    val t: Long
)
