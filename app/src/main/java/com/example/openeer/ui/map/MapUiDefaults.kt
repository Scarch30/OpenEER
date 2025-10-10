package com.example.openeer.ui.map

object MapUiDefaults {
    const val MIN_DISTANCE_METERS = 20f
    const val MIN_TIME_BETWEEN_UPDATES_MS = 1_000L
    const val REQUEST_INTERVAL_MS = 1_500L
    const val MAX_ROUTE_POINTS = 500
    const val ROUTE_LINE_COLOR = "#FF2E7D32"
    const val ROUTE_LINE_WIDTH = 4f
    const val ROUTE_BOUNDS_PADDING_DP = 48
    const val HERE_COOLDOWN_MS = 15_000L
    const val HERE_COOLDOWN_DISTANCE_M = 30f

    const val CLUSTER_RADIUS_PX = 48.0
    const val CLUSTER_FEW_THRESHOLD = 1
    const val CLUSTER_MANY_THRESHOLD = 4

    const val DEFAULT_CAMERA_ZOOM = 5.0
    const val TAP_MIN_ZOOM = 13.5
    const val RECENTER_MIN_ZOOM = 14.0
    const val FOCUS_NOTE_ZOOM = 15.0
    const val SNAPSHOT_CENTER_ZOOM = 15.0

    const val FIT_ALL_PADDING_PX = 64

    const val SYMBOL_LAYER_ICON_SIZE = 1.15f
    val SYMBOL_LAYER_TEXT_OFFSET = arrayOf(0f, 1.5f)
    const val SYMBOL_LAYER_TEXT_SIZE = 12f
    const val SYMBOL_LAYER_HALO_WIDTH = 1.6f

    const val SNAPSHOT_TIMEOUT_MS = 1_500L

    const val PIN_DEFAULT_SIZE_DP = 22
    const val PIN_HERE_SIZE_DP = 24
    const val PIN_SELECTION_SIZE_DP = 18
}
