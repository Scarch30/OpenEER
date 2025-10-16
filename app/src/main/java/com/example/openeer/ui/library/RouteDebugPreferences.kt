package com.example.openeer.ui.library

import android.content.Context
import android.content.SharedPreferences
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.util.isDebugBuild

internal object RouteDebugPreferences {
    private const val PREFS_NAME = "route_debug_overlay"

    internal const val KEY_ENABLED = "route_debug_overlay_enabled"
    internal const val KEY_EPSILON = "route_eps_m"
    internal const val KEY_MIN_INTERVAL = "route_min_interval_ms"
    internal const val KEY_MIN_DISPLACEMENT = "route_min_disp_m"
    internal const val KEY_MAX_ACCURACY = "route_max_acc_m"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isOverlayToggleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setOverlayToggleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        MapUiDefaults.DEBUG_ROUTE = context.isDebugBuild() && enabled
    }

    fun refreshDebugFlag(context: Context) {
        MapUiDefaults.DEBUG_ROUTE = context.isDebugBuild() && isOverlayToggleEnabled(context)
    }

    fun shouldExecuteOverlayCode(context: Context): Boolean {
        return context.isDebugBuild() || isOverlayToggleEnabled(context)
    }
}
