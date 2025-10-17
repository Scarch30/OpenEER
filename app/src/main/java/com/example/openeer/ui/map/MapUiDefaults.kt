package com.example.openeer.ui.map

/**
 * Constantes UI & seuils carte (aucune logique ici).
 * Doit rester stable et réutilisable par MapFragment et les helpers.
 */
object MapUiDefaults {
    const val MIN_DISTANCE_METERS = 20f

    // Cadence requête capteurs / GPS
    const val MIN_TIME_BETWEEN_UPDATES_MS = 1_000L
    const val REQUEST_INTERVAL_MS = 1_000L   // ← 1500 → 1000 ms

    const val MAX_ROUTE_POINTS = 500

    // Intervalle minimal entre points acceptés (runtime-tunable)
    const val ROUTE_MIN_INTERVAL_MS_DEFAULT = 1_000L   // ← 1200 → 1000 ms
    var ROUTE_MIN_INTERVAL_MS: Long = ROUTE_MIN_INTERVAL_MS_DEFAULT

    // Déplacement minimal (runtime-tunable) — base plus permissive pour la marche
    const val ROUTE_MIN_DISPLACEMENT_M_DEFAULT = 6f     // ← 8 → 6 m
    var ROUTE_MIN_DISPLACEMENT_M: Float = ROUTE_MIN_DISPLACEMENT_M_DEFAULT

    // Précision max acceptée
    const val ROUTE_MAX_ACCURACY_M_DEFAULT = 25f
    var ROUTE_MAX_ACCURACY_M: Float = ROUTE_MAX_ACCURACY_M_DEFAULT

    // ----------------------------------------------------------------------------
    // Simplification (Douglas–Peucker)
    // ----------------------------------------------------------------------------
    // Historique (garde pour compat) : epsilon “générique”
    const val ROUTE_SIMPLIFY_EPSILON_M_DEFAULT = 6f     // ← 8 → 6 m
    var ROUTE_SIMPLIFY_EPSILON_M: Float = ROUTE_SIMPLIFY_EPSILON_M_DEFAULT

    // Nouveaux ε séparés :
    // - render/snapshot : préserver la courbure (petit ε)
    // - URL Maps       : compacter l’URL (ε un peu plus grand)
    const val ROUTE_SIMPLIFY_EPSILON_RENDER_M_DEFAULT = 2f
    var ROUTE_SIMPLIFY_EPSILON_RENDER_M: Float = ROUTE_SIMPLIFY_EPSILON_RENDER_M_DEFAULT

    const val ROUTE_SIMPLIFY_EPSILON_URL_M_DEFAULT = 8f
    var ROUTE_SIMPLIFY_EPSILON_URL_M: Float = ROUTE_SIMPLIFY_EPSILON_URL_M_DEFAULT

    // Bornes
    const val ROUTE_MAX_SPEED_MPS = 15f

    // Rejet de micro-jitter
    const val ROUTE_JITTER_REJECT_M = 3f

    // EMA : plus de poids au point courant quand on va lentement
    const val ROUTE_EMA_SLOW_ALPHA = 0.40f   // marche lente → très lissé
    const val ROUTE_EMA_MED_ALPHA  = 0.55f   // vélo / jogging
    const val ROUTE_EMA_FAST_ALPHA = 0.75f   // voiture → plus réactif

    // Rendu de la route
    const val ROUTE_LINE_COLOR = "#FF2E7D32"
    const val ROUTE_LINE_WIDTH = 4f
    const val ROUTE_BOUNDS_PADDING_DP = 192 // (tu l’as déjà poussé)

    // Zoom et cadrage du snapshot
    const val ROUTE_SNAPSHOT_MIN_SPAN_M = 1000f   // distance mini couverte par le cadre
    const val ROUTE_SNAPSHOT_PAD_FACTOR = 5.5f   // élargissement global (air autour)
    const val ROUTE_SNAPSHOT_PADDING_DP = 320   // padding interne pour fit bounds

    // Mode debug tracé
    var DEBUG_ROUTE: Boolean = false
    const val DEBUG_ROUTE_RAW_COLOR   = "#AAE91E63"
    const val DEBUG_ROUTE_SIMPL_COLOR = "#AA2196F3"

    // “Ici” (cooldown)
    const val HERE_COOLDOWN_MS = 15_000L
    const val HERE_COOLDOWN_DISTANCE_M = 30f

    const val CLOSE_MAP_AFTER_SNAPSHOT = true
}
