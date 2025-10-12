package com.example.openeer.ui.map

/**
 * Constantes UI & seuils carte (aucune logique ici).
 * Doit rester stable et réutilisable par MapFragment et les helpers.
 */
object MapUiDefaults {
    const val MIN_DISTANCE_METERS = 20f
    const val MIN_TIME_BETWEEN_UPDATES_MS = 1_000L
    const val REQUEST_INTERVAL_MS = 1_500L
    const val MAX_ROUTE_POINTS = 500

    // Rendu de la route
    const val ROUTE_LINE_COLOR = "#FF2E7D32"
    const val ROUTE_LINE_WIDTH = 4f
    const val ROUTE_BOUNDS_PADDING_DP = 48

    // “Ici” (cooldown)
    const val HERE_COOLDOWN_MS = 15_000L
    const val HERE_COOLDOWN_DISTANCE_M = 30f
}
