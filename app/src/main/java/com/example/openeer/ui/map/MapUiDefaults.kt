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

    const val ROUTE_MIN_INTERVAL_MS_DEFAULT = 1_200L
    var ROUTE_MIN_INTERVAL_MS: Long = ROUTE_MIN_INTERVAL_MS_DEFAULT

    const val ROUTE_MIN_DISPLACEMENT_M_DEFAULT = 8f
    var ROUTE_MIN_DISPLACEMENT_M: Float = ROUTE_MIN_DISPLACEMENT_M_DEFAULT

    const val ROUTE_MAX_ACCURACY_M_DEFAULT = 25f
    var ROUTE_MAX_ACCURACY_M: Float = ROUTE_MAX_ACCURACY_M_DEFAULT

    const val ROUTE_SIMPLIFY_EPSILON_M_DEFAULT = 8f
    var ROUTE_SIMPLIFY_EPSILON_M: Float = ROUTE_SIMPLIFY_EPSILON_M_DEFAULT

    const val ROUTE_MAX_SPEED_MPS = 15f

    // Rendu de la route
    const val ROUTE_LINE_COLOR = "#FF2E7D32"
    const val ROUTE_LINE_WIDTH = 4f
    const val ROUTE_BOUNDS_PADDING_DP = 48

    // Mode debug tracé
    var DEBUG_ROUTE: Boolean = true // défaut false ; activé ici pour validation
    const val DEBUG_ROUTE_RAW_COLOR = "#AAE91E63"
    const val DEBUG_ROUTE_SIMPL_COLOR = "#AA2196F3"

    // “Ici” (cooldown)
    const val HERE_COOLDOWN_MS = 15_000L
    const val HERE_COOLDOWN_DISTANCE_M = 30f
}
